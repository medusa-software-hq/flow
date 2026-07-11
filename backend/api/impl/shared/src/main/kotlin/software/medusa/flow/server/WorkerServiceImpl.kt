package software.medusa.flow.server

import io.grpc.Status
import software.medusa.flow.v1.AppendSessionEventRequest
import software.medusa.flow.v1.AppendSessionEventResponse
import software.medusa.flow.v1.ClaimNextSessionRequest
import software.medusa.flow.v1.ClaimNextSessionResponse
import software.medusa.flow.v1.CompleteSessionRequest
import software.medusa.flow.v1.CompleteSessionResponse
import software.medusa.flow.v1.FailSessionRequest
import software.medusa.flow.v1.FailSessionResponse
import software.medusa.flow.v1.HeartbeatRequest
import software.medusa.flow.v1.HeartbeatResponse
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventResponse
import software.medusa.flow.v1.claimNextSessionResponse
import software.medusa.flow.v1.completeSessionResponse
import software.medusa.flow.v1.failSessionResponse
import software.medusa.flow.v1.heartbeatResponse

/**
 * Worker-facing [WorkerService][WorkerServiceGrpcKt]: claim/append/heartbeat/complete/fail over the
 * [SessionStore].
 *
 * The auth decorator only verifies the caller holds a validly signed Google ID token — user or
 * service account. Every method here additionally checks the verified email against
 * [workerAuthorizer] before touching the store, so a regular user's (validly signed) token is
 * rejected with `PERMISSION_DENIED` rather than allowed to act as the worker.
 *
 * Per-session RPCs translate [GuardedResult.PreconditionFailed] (the session is absent or no longer
 * `RUNNING`) to `FAILED_PRECONDITION`, per the state-machine contract in `design/02-api.md`.
 */
class WorkerServiceImpl(
    private val sessionStore: SessionStore,
    private val workerAuthorizer: WorkerAuthorizer,
    private val issuePipelineStore: IssuePipelineStore,
) : WorkerServiceGrpcKt.WorkerServiceCoroutineImplBase() {
  private companion object {
    private val prNumberRegex = Regex("""/pull/(\d+)""")
  }

  private fun requireAuthorizedWorker() {
    val email =
        AuthenticatedUser.currentEmail()
            ?: throw Status.UNAUTHENTICATED.withDescription("No authenticated caller")
                .asRuntimeException()

    if (!workerAuthorizer.isAuthorized(email)) {
      throw Status.PERMISSION_DENIED.withDescription("$email is not an authorized worker")
          .asRuntimeException()
    }
  }

  private fun <T> GuardedResult<T>.orFailedPrecondition(
      sessionId: String,
  ): T =
      when (this) {
        is GuardedResult.Applied -> value
        GuardedResult.PreconditionFailed ->
            throw Status.FAILED_PRECONDITION.withDescription(
                    "Session $sessionId is not RUNNING",
                )
                .asRuntimeException()
      }

  override suspend fun claimNextSession(
      request: ClaimNextSessionRequest,
  ): ClaimNextSessionResponse {
    requireAuthorizedWorker()

    val claimed = sessionStore.claimNext()

    // Include the issue linkage so the worker can publish an issue-aware PR (story 09).
    return claimNextSessionResponse {
      claimed?.let { session = it.toProto(issuePipelineStore.findBySessionId(it.id)) }
    }
  }

  override suspend fun appendSessionEvent(
      request: AppendSessionEventRequest,
  ): AppendSessionEventResponse {
    requireAuthorizedWorker()

    val kind =
        request.kind.toDomainOrNull()
            ?: throw Status.INVALID_ARGUMENT.withDescription("kind must be set")
                .asRuntimeException()

    sessionStore
        .appendEvent(id = SessionId(request.sessionId), kind = kind, message = request.message)
        .orFailedPrecondition(request.sessionId)

    return appendSessionEventResponse {}
  }

  override suspend fun heartbeat(
      request: HeartbeatRequest,
  ): HeartbeatResponse {
    requireAuthorizedWorker()

    sessionStore.heartbeat(SessionId(request.sessionId)).orFailedPrecondition(request.sessionId)

    return heartbeatResponse {}
  }

  override suspend fun completeSession(
      request: CompleteSessionRequest,
  ): CompleteSessionResponse {
    requireAuthorizedWorker()

    val sessionId = SessionId(request.sessionId)
    sessionStore
        .complete(id = sessionId, prUrl = request.prUrl)
        .orFailedPrecondition(request.sessionId)

    // Advance the linked pipeline IN_PROGRESS → PR_OPEN (latency; the reconcile observe phase is
    // the
    // correctness backstop if this doesn't run). A manual session has no pipeline — no-op.
    advanceLinkedPipeline(sessionId) { pipeline ->
      issuePipelineStore.markPrOpen(
          pipeline.id,
          prNumber = parsePrNumber(request.prUrl),
          prUrl = request.prUrl,
      )
    }

    return completeSessionResponse {}
  }

  override suspend fun failSession(
      request: FailSessionRequest,
  ): FailSessionResponse {
    requireAuthorizedWorker()

    val sessionId = SessionId(request.sessionId)
    sessionStore
        .fail(id = sessionId, failureSummary = request.failureSummary)
        .orFailedPrecondition(request.sessionId)

    advanceLinkedPipeline(sessionId) { pipeline ->
      issuePipelineStore.markFailed(
          pipeline.id,
          failureSummary =
              "The Flow session for this issue failed:\n\n${request.failureSummary}\n\n" +
                  "Clear this pipeline to let Flow try the issue again.",
      )
    }

    return failSessionResponse {}
  }

  /**
   * Runs [transition] against the pipeline linked to [sessionId], if it exists and is IN_PROGRESS.
   */
  private suspend fun advanceLinkedPipeline(
      sessionId: SessionId,
      transition: suspend (IssuePipeline) -> PipelineTransition,
  ) {
    val pipeline = issuePipelineStore.findBySessionId(sessionId) ?: return
    if (pipeline.state == IssuePipelineState.InProgress) transition(pipeline)
  }

  private fun parsePrNumber(
      prUrl: String,
  ): Int = prNumberRegex.find(prUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
