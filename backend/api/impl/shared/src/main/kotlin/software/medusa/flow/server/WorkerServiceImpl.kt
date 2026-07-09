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
) : WorkerServiceGrpcKt.WorkerServiceCoroutineImplBase() {
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

    return claimNextSessionResponse { claimed?.let { session = it.toProto() } }
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

    sessionStore
        .complete(id = SessionId(request.sessionId), prUrl = request.prUrl)
        .orFailedPrecondition(request.sessionId)

    return completeSessionResponse {}
  }

  override suspend fun failSession(
      request: FailSessionRequest,
  ): FailSessionResponse {
    requireAuthorizedWorker()

    sessionStore
        .fail(id = SessionId(request.sessionId), failureSummary = request.failureSummary)
        .orFailedPrecondition(request.sessionId)

    return failSessionResponse {}
  }
}
