package software.medusa.flow.server

import io.grpc.Status
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import software.medusa.flow.v1.ClearIssuePipelineRequest
import software.medusa.flow.v1.ClearIssuePipelineResponse
import software.medusa.flow.v1.IssuePipelineTransition
import software.medusa.flow.v1.ListIssuePipelinesRequest
import software.medusa.flow.v1.ListIssuePipelinesResponse
import software.medusa.flow.v1.PipelineServiceGrpcKt
import software.medusa.flow.v1.WatchIssuePipelinesRequest
import software.medusa.flow.v1.clearIssuePipelineResponse
import software.medusa.flow.v1.issuePipelineTransition
import software.medusa.flow.v1.listIssuePipelinesResponse

/**
 * User-facing [PipelineService][PipelineServiceGrpcKt]: the auto-mode UI's read of what the
 * reconciler is doing per repo, plus the single human control — clearing a `FAILED` pipeline so its
 * repo mutex releases and the issue becomes pickable again.
 *
 * Runs behind the shared Google-ID-token auth decorator (any signed-in user), like
 * [SessionServiceImpl] — not the SA allowlist that gates [ReconcileServiceImpl].
 */
class PipelineServiceImpl(
    private val issuePipelineStore: IssuePipelineStore,
    private val githubOutboxStore: GithubOutboxStore,
    private val sessionStore: SessionStore,
    // How often [watchIssuePipelines] re-polls the store for changes. Overridable so tests observe
    // a transition in milliseconds instead of waiting on the production cadence.
    private val watchPollInterval: Duration = 2.seconds,
) : PipelineServiceGrpcKt.PipelineServiceCoroutineImplBase() {
  override suspend fun listIssuePipelines(
      request: ListIssuePipelinesRequest,
  ): ListIssuePipelinesResponse {
    val repoFilter = request.repoFullName.takeIf { it.isNotEmpty() }
    val pipelines = issuePipelineStore.list(repoFilter)
    val stuckKeys = stuckOutboxKeys()

    return listIssuePipelinesResponse {
      this.pipelines += pipelines.map {
        it.toProto(outboxStuck = (it.repoFullName to it.issueNumber) in stuckKeys)
      }
    }
  }

  /**
   * Polls the store every [watchPollInterval] and diffs against the previous poll's per-pipeline
   * state, emitting one [IssuePipelineTransition] per pipeline whose state changed (including a
   * brand-new pipeline, which reads as a transition from `UNSPECIFIED`). The first poll only seeds
   * the baseline — it emits nothing, so a client that's just started watching isn't replayed every
   * pipeline's current state as if it just changed. Never completes; the caller (the CLI, via gRPC
   * cancellation on Ctrl-C) is what ends the stream.
   */
  override fun watchIssuePipelines(
      request: WatchIssuePipelinesRequest
  ): Flow<IssuePipelineTransition> = flow {
    val repoFilter = request.repoFullName.takeIf { it.isNotEmpty() }
    val lastState = mutableMapOf<IssuePipelineId, IssuePipelineState>()
    var seeded = false

    while (true) {
      val pipelines = issuePipelineStore.list(repoFilter)
      val stuckKeys = stuckOutboxKeys()
      val seenIds = mutableSetOf<IssuePipelineId>()

      for (pipeline in pipelines) {
        seenIds += pipeline.id
        val previousState = lastState[pipeline.id]
        if (seeded && previousState != pipeline.state) {
          emit(
              issuePipelineTransition {
                this.pipeline =
                    pipeline.toProto(
                        outboxStuck = (pipeline.repoFullName to pipeline.issueNumber) in stuckKeys,
                    )
                previousState?.let { oldState = it.toProto() }
                observedAt = Clock.System.now().toProtoTimestamp()
              },
          )
        }
        lastState[pipeline.id] = pipeline.state
      }
      // `list()` is bounded/newest-first, so a pipeline can age out of the page without ever
      // reaching a terminal state; drop it from the baseline rather than let the map grow
      // unbounded across a long-lived watch.
      lastState.keys.retainAll(seenIds)

      seeded = true
      delay(watchPollInterval)
    }
  }

  /**
   * `outbox_stuck` is derived, not stored: a pipeline is flagged when a stuck outbox entry exists
   * for its (repo, issue). One query, keyed by (repo, issue), covers a whole page/poll.
   */
  private suspend fun stuckOutboxKeys(): Set<Pair<String, Int>> =
      githubOutboxStore
          .stuckEntries(GithubOutboxStore.defaultStuckAttempts)
          .map { it.repoFullName to it.issueNumber }
          .toSet()

  override suspend fun clearIssuePipeline(
      request: ClearIssuePipelineRequest,
  ): ClearIssuePipelineResponse {
    val id = IssuePipelineId(request.id)

    // Distinguish "no such pipeline" (NOT_FOUND) from "exists but not clearable" — clear() only
    // applies to an uncleared FAILED row, rejecting anything else.
    issuePipelineStore.get(id)
        ?: throw Status.NOT_FOUND.withDescription("No such pipeline: ${request.id}")
            .asRuntimeException()

    return when (val transition = issuePipelineStore.clear(id)) {
      is PipelineTransition.Applied -> {
        // Abandon the cleared pipeline's session so a worker still holding it can't publish a PR
        // that a re-pick would duplicate. Best-effort: fail() only applies to a RUNNING session
        // (the "worker lost but still alive" edge) and no-ops for an already-terminal one.
        transition.pipeline.sessionId?.let { sessionStore.fail(it, clearedFailureSummary) }
        clearIssuePipelineResponse { pipeline = transition.pipeline.toProto() }
      }
      is PipelineTransition.Rejected ->
          throw Status.FAILED_PRECONDITION.withDescription(transition.reason).asRuntimeException()
    }
  }

  private companion object {
    const val clearedFailureSummary = "Pipeline cleared — this session was abandoned."
  }
}
