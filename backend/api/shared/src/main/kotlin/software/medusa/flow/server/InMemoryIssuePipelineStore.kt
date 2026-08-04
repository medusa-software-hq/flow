package software.medusa.flow.server

import java.util.UUID
import kotlin.time.Instant
import software.medusa.flow.server.IssuePipelineStore.Companion.labelFailed
import software.medusa.flow.server.IssuePipelineStore.Companion.labelFor
import software.medusa.flow.server.IssuePipelineStore.Companion.labelInProgress
import software.medusa.flow.server.IssuePipelineStore.Companion.labelPrOpen

/**
 * An in-memory [IssuePipelineStore] for tests and local runs, enforcing the same state machine and
 * repo mutex as the Postgres store. Shares [backend] (and its lock) with
 * [InMemoryGithubOutboxStore] so each transition and the outbox entries it enqueues apply
 * atomically.
 */
class InMemoryIssuePipelineStore(
    private val backend: InMemoryPipelineBackend = InMemoryPipelineBackend(),
) : IssuePipelineStore {
  private val clock = backend.clock

  override suspend fun pick(
      repoFullName: String,
      issueNumber: Int,
      issueTitle: String,
      issueUrl: String,
      sessionId: SessionId,
      shadowSessionId: SessionId,
  ): PickResult =
      synchronized(backend.lock) {
        if (isRepoBusyLocked(repoFullName)) return@synchronized PickResult.RepoBusy

        val now = clock.now()
        val pipeline =
            IssuePipeline(
                id = IssuePipelineId(UUID.randomUUID().toString()),
                repoFullName = repoFullName,
                issueNumber = issueNumber,
                issueTitle = issueTitle,
                issueUrl = issueUrl,
                state = IssuePipelineState.InProgress,
                sessionId = sessionId,
                shadowSessionId = shadowSessionId,
                prNumber = null,
                prUrl = null,
                mergeCommitSha = null,
                failureSummary = null,
                createdAt = now,
                updatedAt = now,
                clearedAt = null,
            )

        backend.pipelinesById[pipeline.id] = pipeline
        backend.enqueueLocked(
            repoFullName,
            issueNumber,
            OutboxAction.AddLabel,
            OutboxPayloads.label(labelInProgress),
        )

        PickResult.Picked(pipeline)
      }

  override suspend fun markPrOpen(
      id: IssuePipelineId,
      prNumber: Int,
      prUrl: String,
  ): PipelineTransition =
      transition(id, from = IssuePipelineState.InProgress) { pipeline ->
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.RemoveLabel,
            OutboxPayloads.label(labelInProgress),
        )
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.AddLabel,
            OutboxPayloads.label(labelPrOpen),
        )
        pipeline.copy(state = IssuePipelineState.PrOpen, prNumber = prNumber, prUrl = prUrl)
      }

  override suspend fun markAwaitingMergeChecks(
      id: IssuePipelineId,
      mergeCommitSha: String,
  ): PipelineTransition =
      // No label change — flow:pr-open covers PR_OPEN and AWAITING_MERGE_CHECKS.
      transition(id, from = IssuePipelineState.PrOpen) { pipeline ->
        pipeline.copy(
            state = IssuePipelineState.AwaitingMergeChecks,
            mergeCommitSha = mergeCommitSha,
        )
      }

  override suspend fun markDone(
      id: IssuePipelineId,
      annotationMarkdown: String,
  ): PipelineTransition =
      transition(id, from = IssuePipelineState.AwaitingMergeChecks) { pipeline ->
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.RemoveLabel,
            OutboxPayloads.label(labelPrOpen),
        )
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.PostComment,
            OutboxPayloads.comment(annotationMarkdown),
        )
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.CloseIssue,
            OutboxPayloads.closeIssue,
        )
        pipeline.copy(state = IssuePipelineState.Done)
      }

  override suspend fun markFailed(
      id: IssuePipelineId,
      failureSummary: String,
  ): PipelineTransition =
      synchronized(backend.lock) {
        val pipeline =
            backend.pipelinesById[id]
                ?: return@synchronized PipelineTransition.Rejected("Pipeline ${id.id} not found")

        if (!pipeline.isLive || pipeline.state == IssuePipelineState.Failed) {
          return@synchronized PipelineTransition.Rejected(
              "Pipeline ${id.id} is ${pipeline.state}, cannot fail",
          )
        }

        labelFor(pipeline.state)?.let { currentLabel ->
          backend.enqueueLocked(
              pipeline.repoFullName,
              pipeline.issueNumber,
              OutboxAction.RemoveLabel,
              OutboxPayloads.label(currentLabel),
          )
        }
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.AddLabel,
            OutboxPayloads.label(labelFailed),
        )
        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.PostComment,
            OutboxPayloads.comment(failureSummary),
        )

        val updated =
            pipeline.copy(
                state = IssuePipelineState.Failed,
                failureSummary = failureSummary,
                updatedAt = clock.now(),
            )
        backend.pipelinesById[id] = updated
        PipelineTransition.Applied(updated)
      }

  override suspend fun clear(
      id: IssuePipelineId,
  ): PipelineTransition =
      synchronized(backend.lock) {
        val pipeline =
            backend.pipelinesById[id]
                ?: return@synchronized PipelineTransition.Rejected("Pipeline ${id.id} not found")

        if (pipeline.state != IssuePipelineState.Failed || pipeline.clearedAt != null) {
          return@synchronized PipelineTransition.Rejected(
              "Only an uncleared FAILED pipeline can be cleared; ${id.id} is ${pipeline.state}",
          )
        }

        backend.enqueueLocked(
            pipeline.repoFullName,
            pipeline.issueNumber,
            OutboxAction.RemoveLabel,
            OutboxPayloads.label(labelFailed),
        )

        val now = clock.now()
        val updated = pipeline.copy(clearedAt = now, updatedAt = now)
        backend.pipelinesById[id] = updated
        PipelineTransition.Applied(updated)
      }

  override suspend fun get(
      id: IssuePipelineId,
  ): IssuePipeline? = synchronized(backend.lock) { backend.pipelinesById[id] }

  override suspend fun findBySessionId(
      sessionId: SessionId,
  ): IssuePipeline? =
      synchronized(backend.lock) {
        // Matches either session — the primary or the shadow — so display enrichment works for
        // both.
        // Primary-only callers (pipeline advancement) filter on sessionId themselves.
        backend.pipelinesById.values.firstOrNull {
          it.sessionId == sessionId || it.shadowSessionId == sessionId
        }
      }

  override suspend fun listLive(): List<IssuePipeline> =
      synchronized(backend.lock) { backend.pipelinesById.values.filter { it.isLive } }

  override suspend fun list(
      repoFullName: String?,
  ): List<IssuePipeline> =
      synchronized(backend.lock) {
        backend.pipelinesById.values
            .filter { repoFullName == null || it.repoFullName == repoFullName }
            .sortedByDescending { it.createdAt }
      }

  override suspend fun isRepoBusy(
      repoFullName: String,
  ): Boolean = synchronized(backend.lock) { isRepoBusyLocked(repoFullName) }

  private fun isRepoBusyLocked(
      repoFullName: String,
  ): Boolean = backend.pipelinesById.values.any { it.repoFullName == repoFullName && it.isLive }

  /**
   * Applies [update] to the pipeline [id] iff it's currently in state [from], enqueuing whatever
   * outbox entries [update] writes atomically. Rejects (changing nothing) otherwise.
   */
  private inline fun transition(
      id: IssuePipelineId,
      from: IssuePipelineState,
      update: (IssuePipeline) -> IssuePipeline,
  ): PipelineTransition =
      synchronized(backend.lock) {
        val pipeline =
            backend.pipelinesById[id]
                ?: return PipelineTransition.Rejected("Pipeline ${id.id} not found")

        if (pipeline.state != from) {
          return PipelineTransition.Rejected(
              "Pipeline ${id.id} is ${pipeline.state}, expected $from",
          )
        }

        val updated = update(pipeline).copy(updatedAt = clockInstant())
        backend.pipelinesById[id] = updated
        PipelineTransition.Applied(updated)
      }

  private fun clockInstant(): Instant = clock.now()
}
