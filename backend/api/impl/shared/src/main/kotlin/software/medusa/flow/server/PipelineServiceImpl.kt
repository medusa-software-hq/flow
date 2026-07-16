package software.medusa.flow.server

import io.grpc.Status
import software.medusa.flow.v1.ClearIssuePipelineRequest
import software.medusa.flow.v1.ClearIssuePipelineResponse
import software.medusa.flow.v1.ListIssuePipelinesRequest
import software.medusa.flow.v1.ListIssuePipelinesResponse
import software.medusa.flow.v1.PipelineServiceGrpcKt
import software.medusa.flow.v1.clearIssuePipelineResponse
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
) : PipelineServiceGrpcKt.PipelineServiceCoroutineImplBase() {
  override suspend fun listIssuePipelines(
      request: ListIssuePipelinesRequest,
  ): ListIssuePipelinesResponse {
    val repoFilter = request.repoFullName.takeIf { it.isNotEmpty() }
    val pipelines = issuePipelineStore.list(repoFilter)

    // `outbox_stuck` is derived, not stored: a pipeline is flagged when a stuck outbox entry exists
    // for its (repo, issue). One query, keyed by (repo, issue), covers the whole page.
    val stuckKeys =
        githubOutboxStore
            .stuckEntries(GithubOutboxStore.defaultStuckAttempts)
            .map { it.repoFullName to it.issueNumber }
            .toSet()

    return listIssuePipelinesResponse {
      this.pipelines += pipelines.map {
        it.toProto(outboxStuck = (it.repoFullName to it.issueNumber) in stuckKeys)
      }
    }
  }

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
      is PipelineTransition.Applied ->
          clearIssuePipelineResponse { pipeline = transition.pipeline.toProto() }
      is PipelineTransition.Rejected ->
          throw Status.FAILED_PRECONDITION.withDescription(transition.reason).asRuntimeException()
    }
  }
}
