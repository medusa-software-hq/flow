package software.medusa.flow.server

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Issue_pipelines
import software.medusa.flow.server.IssuePipelineStore.Companion.labelFailed
import software.medusa.flow.server.IssuePipelineStore.Companion.labelFor
import software.medusa.flow.server.IssuePipelineStore.Companion.labelInProgress
import software.medusa.flow.server.IssuePipelineStore.Companion.labelPrOpen

/**
 * A Postgres-backed [IssuePipelineStore] (SQLDelight queries over the Flyway-owned schema). Every
 * transition and the outbox entries it enqueues run inside one `database` transaction, and the
 * partial unique indexes make a double-pick a SQL constraint violation rather than an app-level
 * race.
 *
 * The outbox is written through the same [database], so a [PostgresGithubOutboxStore] over the same
 * database reads exactly what these transitions enqueue.
 */
class PostgresIssuePipelineStore(
    private val database: FlowDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : IssuePipelineStore {
  private val pipelines = database.issuePipelineQueries
  private val outbox = database.githubOutboxQueries

  override suspend fun pick(
      repoFullName: String,
      issueNumber: Int,
      issueTitle: String,
      issueUrl: String,
      sessionId: SessionId,
  ): PickResult =
      withContext(Dispatchers.IO) {
        database.transactionWithResult {
          if (pipelines.countLiveForRepo(repoFullName).executeAsOne() > 0) {
            return@transactionWithResult PickResult.RepoBusy
          }

          val now = clock.instant()
          val id = IssuePipelineId(UUID.randomUUID().toString())

          pipelines.insertIssuePipeline(
              id = id.id,
              repo_full_name = repoFullName,
              issue_number = issueNumber,
              issue_title = issueTitle,
              issue_url = issueUrl,
              state = IssuePipelineState.InProgress.toDbValue(),
              session_id = sessionId.id,
              created_at = now.toOffsetDateTime(),
              updated_at = now.toOffsetDateTime(),
          )

          enqueue(
              repoFullName,
              issueNumber,
              OutboxAction.AddLabel,
              OutboxPayloads.label(labelInProgress),
              now,
          )

          PickResult.Picked(pipelines.selectById(id.id).executeAsOne().toDomain())
        }
      }

  override suspend fun markPrOpen(
      id: IssuePipelineId,
      prNumber: Int,
      prUrl: String,
  ): PipelineTransition =
      withContext(Dispatchers.IO) {
        database.transactionWithResult {
          val now = clock.instant()
          val updated =
              pipelines
                  .markPrOpenIfInProgress(prNumber, prUrl, now.toOffsetDateTime(), id.id)
                  .executeAsOneOrNull() ?: return@transactionWithResult rejected(id)

          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.RemoveLabel,
              OutboxPayloads.label(labelInProgress),
              now,
          )
          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.AddLabel,
              OutboxPayloads.label(labelPrOpen),
              now,
          )

          PipelineTransition.Applied(updated.toDomain())
        }
      }

  override suspend fun markAwaitingMergeChecks(
      id: IssuePipelineId,
      mergeCommitSha: String,
  ): PipelineTransition =
      withContext(Dispatchers.IO) {
        // No outbox: flow:pr-open already covers AWAITING_MERGE_CHECKS.
        val updated =
            pipelines
                .markAwaitingIfPrOpen(mergeCommitSha, clock.instant().toOffsetDateTime(), id.id)
                .executeAsOneOrNull()
        if (updated == null) rejected(id) else PipelineTransition.Applied(updated.toDomain())
      }

  override suspend fun markDone(
      id: IssuePipelineId,
      annotationMarkdown: String,
  ): PipelineTransition =
      withContext(Dispatchers.IO) {
        database.transactionWithResult {
          val now = clock.instant()
          val updated =
              pipelines.markDoneIfAwaiting(now.toOffsetDateTime(), id.id).executeAsOneOrNull()
                  ?: return@transactionWithResult rejected(id)

          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.RemoveLabel,
              OutboxPayloads.label(labelPrOpen),
              now,
          )
          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.PostComment,
              OutboxPayloads.comment(annotationMarkdown),
              now,
          )
          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.CloseIssue,
              OutboxPayloads.closeIssue,
              now,
          )

          PipelineTransition.Applied(updated.toDomain())
        }
      }

  override suspend fun markFailed(
      id: IssuePipelineId,
      failureSummary: String,
  ): PipelineTransition =
      withContext(Dispatchers.IO) {
        database.transactionWithResult {
          // Read the current row first: the label to remove depends on the *prior* state, which the
          // post-update RETURNING row no longer carries.
          val current =
              pipelines.selectById(id.id).executeAsOneOrNull()?.toDomain()
                  ?: return@transactionWithResult rejected(id)
          val priorLabel = labelFor(current.state)

          val now = clock.instant()
          val updated =
              pipelines
                  .markFailedIfLive(failureSummary, now.toOffsetDateTime(), id.id)
                  .executeAsOneOrNull() ?: return@transactionWithResult rejected(id)

          priorLabel?.let {
            enqueue(
                updated.repo_full_name,
                updated.issue_number,
                OutboxAction.RemoveLabel,
                OutboxPayloads.label(it),
                now,
            )
          }
          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.AddLabel,
              OutboxPayloads.label(labelFailed),
              now,
          )
          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.PostComment,
              OutboxPayloads.comment(failureSummary),
              now,
          )

          PipelineTransition.Applied(updated.toDomain())
        }
      }

  override suspend fun clear(
      id: IssuePipelineId,
  ): PipelineTransition =
      withContext(Dispatchers.IO) {
        database.transactionWithResult {
          val now = clock.instant()
          val updated =
              pipelines.clearIfFailed(now.toOffsetDateTime(), id.id).executeAsOneOrNull()
                  ?: return@transactionWithResult rejected(id)

          enqueue(
              updated.repo_full_name,
              updated.issue_number,
              OutboxAction.RemoveLabel,
              OutboxPayloads.label(labelFailed),
              now,
          )

          PipelineTransition.Applied(updated.toDomain())
        }
      }

  override suspend fun get(
      id: IssuePipelineId,
  ): IssuePipeline? =
      withContext(Dispatchers.IO) { pipelines.selectById(id.id).executeAsOneOrNull()?.toDomain() }

  override suspend fun listLive(): List<IssuePipeline> =
      withContext(Dispatchers.IO) { pipelines.listLive().executeAsList().map { it.toDomain() } }

  override suspend fun list(
      repoFullName: String?,
  ): List<IssuePipeline> =
      withContext(Dispatchers.IO) {
        val rows =
            if (repoFullName == null) pipelines.listAll().executeAsList()
            else pipelines.listByRepo(repoFullName).executeAsList()
        rows.map { it.toDomain() }
      }

  override suspend fun isRepoBusy(
      repoFullName: String,
  ): Boolean =
      withContext(Dispatchers.IO) { pipelines.countLiveForRepo(repoFullName).executeAsOne() > 0 }

  private fun rejected(
      id: IssuePipelineId,
  ): PipelineTransition =
      PipelineTransition.Rejected("Pipeline ${id.id} not in an applicable state")

  private fun enqueue(
      repoFullName: String,
      issueNumber: Int,
      action: OutboxAction,
      payload: String,
      now: Instant,
  ) {
    val seq = outbox.selectNextSeq(repoFullName, issueNumber).executeAsOne()
    outbox.insertOutboxEntry(
        id = UUID.randomUUID().toString(),
        repo_full_name = repoFullName,
        issue_number = issueNumber,
        seq = seq,
        action = action.toDbValue(),
        payload = payload,
        now = now.toOffsetDateTime(),
    )
  }

  private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

  private fun Issue_pipelines.toDomain(): IssuePipeline =
      IssuePipeline(
          id = IssuePipelineId(id),
          repoFullName = repo_full_name,
          issueNumber = issue_number,
          issueTitle = issue_title,
          issueUrl = issue_url,
          state = parsePipelineState(state),
          sessionId = session_id?.let { SessionId(it) },
          prNumber = pr_number,
          prUrl = pr_url,
          mergeCommitSha = merge_commit_sha,
          failureSummary = failure_summary,
          createdAt = created_at.toInstant(),
          updatedAt = updated_at.toInstant(),
          clearedAt = cleared_at?.toInstant(),
      )
}

internal fun IssuePipelineState.toDbValue(): String =
    when (this) {
      IssuePipelineState.InProgress -> "IN_PROGRESS"
      IssuePipelineState.PrOpen -> "PR_OPEN"
      IssuePipelineState.AwaitingMergeChecks -> "AWAITING_MERGE_CHECKS"
      IssuePipelineState.Done -> "DONE"
      IssuePipelineState.Failed -> "FAILED"
    }

internal fun parsePipelineState(
    value: String,
): IssuePipelineState =
    when (value) {
      "IN_PROGRESS" -> IssuePipelineState.InProgress
      "PR_OPEN" -> IssuePipelineState.PrOpen
      "AWAITING_MERGE_CHECKS" -> IssuePipelineState.AwaitingMergeChecks
      "DONE" -> IssuePipelineState.Done
      "FAILED" -> IssuePipelineState.Failed
      else -> error("Unknown issue pipeline state: $value")
    }

internal fun OutboxAction.toDbValue(): String =
    when (this) {
      OutboxAction.AddLabel -> "ADD_LABEL"
      OutboxAction.RemoveLabel -> "REMOVE_LABEL"
      OutboxAction.PostComment -> "POST_COMMENT"
      OutboxAction.CloseIssue -> "CLOSE_ISSUE"
    }

internal fun parseOutboxAction(
    value: String,
): OutboxAction =
    when (value) {
      "ADD_LABEL" -> OutboxAction.AddLabel
      "REMOVE_LABEL" -> OutboxAction.RemoveLabel
      "POST_COMMENT" -> OutboxAction.PostComment
      "CLOSE_ISSUE" -> OutboxAction.CloseIssue
      else -> error("Unknown outbox action: $value")
    }
