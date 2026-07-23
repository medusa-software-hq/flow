package software.medusa.flow.server

import io.grpc.Status
import software.medusa.flow.v1.AppendSessionEventRequest
import software.medusa.flow.v1.AppendSessionEventResponse
import software.medusa.flow.v1.ClaimNextJobRequest
import software.medusa.flow.v1.ClaimNextJobResponse
import software.medusa.flow.v1.ClaimNextSessionRequest
import software.medusa.flow.v1.ClaimNextSessionResponse
import software.medusa.flow.v1.CompleteSessionRequest
import software.medusa.flow.v1.CompleteSessionResponse
import software.medusa.flow.v1.FailSessionRequest
import software.medusa.flow.v1.FailSessionResponse
import software.medusa.flow.v1.HeartbeatRequest
import software.medusa.flow.v1.HeartbeatResponse
import software.medusa.flow.v1.ListWorkersRequest
import software.medusa.flow.v1.ListWorkersResponse
import software.medusa.flow.v1.RegisterWorkerRequest
import software.medusa.flow.v1.RegisterWorkerResponse
import software.medusa.flow.v1.SessionWriteAck
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventResponse
import software.medusa.flow.v1.claimNextJobResponse
import software.medusa.flow.v1.claimNextSessionResponse
import software.medusa.flow.v1.completeSessionResponse
import software.medusa.flow.v1.failSessionResponse
import software.medusa.flow.v1.heartbeatResponse
import software.medusa.flow.v1.listWorkersResponse
import software.medusa.flow.v1.registerWorkerResponse

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
    private val workerStore: WorkerStore,
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
        // The terminal writes (complete/fail) don't carry a write-ack, so an aborted or
        // otherwise-not-RUNNING session both surface as FAILED_PRECONDITION here; the guard already
        // ensured the write didn't apply, so the ABORTED state is preserved either way.
        GuardedResult.Aborted,
        GuardedResult.PreconditionFailed ->
            throw Status.FAILED_PRECONDITION.withDescription(
                    "Session $sessionId is not RUNNING",
                )
                .asRuntimeException()
      }

  /**
   * Maps a signal-write result (heartbeat / append event) to the response write-ack: `ACCEPTED`
   * while RUNNING, `ABORTED` once the session is aborted (the worker's cue to stop — not an error),
   * and a `FAILED_PRECONDITION` for a genuinely-gone session.
   */
  private fun <T> GuardedResult<T>.toWriteAck(
      sessionId: String,
  ): SessionWriteAck =
      when (this) {
        is GuardedResult.Applied -> SessionWriteAck.SESSION_WRITE_ACK_ACCEPTED
        GuardedResult.Aborted -> SessionWriteAck.SESSION_WRITE_ACK_ABORTED
        GuardedResult.PreconditionFailed ->
            throw Status.FAILED_PRECONDITION.withDescription("Session $sessionId is not RUNNING")
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

  override suspend fun claimNextJob(
      request: ClaimNextJobRequest,
  ): ClaimNextJobResponse {
    requireAuthorizedWorker()

    val claimed = sessionStore.claimNextJob()

    // Each session carries its own issue linkage so the worker publishes an issue-aware PR per
    // engine (both share the pipeline; only the primary drives its state — see
    // advanceLinkedPipeline).
    return claimNextJobResponse {
      sessions += claimed.map { it.toProto(issuePipelineStore.findBySessionId(it.id)) }
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

    val writeAck =
        sessionStore
            .appendEvent(
                id = SessionId(request.sessionId),
                kind = kind,
                message = request.message,
                costUsd = if (request.hasCostUsd()) request.costUsd else null,
            )
            .toWriteAck(request.sessionId)

    return appendSessionEventResponse { ack = writeAck }
  }

  override suspend fun heartbeat(
      request: HeartbeatRequest,
  ): HeartbeatResponse {
    requireAuthorizedWorker()

    val writeAck =
        sessionStore.heartbeat(SessionId(request.sessionId)).toWriteAck(request.sessionId)

    return heartbeatResponse { ack = writeAck }
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

  override suspend fun registerWorker(
      request: RegisterWorkerRequest,
  ): RegisterWorkerResponse {
    requireAuthorizedWorker()

    if (request.workerId.isBlank()) {
      throw Status.INVALID_ARGUMENT.withDescription("worker_id must be set").asRuntimeException()
    }

    workerStore.register(
        workerId = request.workerId,
        workerVersion = request.workerVersion,
        imageDigest = request.imageDigest,
    )

    return registerWorkerResponse {}
  }

  override suspend fun listWorkers(
      request: ListWorkersRequest,
  ): ListWorkersResponse {
    requireAuthorizedWorker()

    val workers = workerStore.list()

    return listWorkersResponse { this.workers.addAll(workers.map { it.toProto() }) }
  }

  /**
   * Runs [transition] against the pipeline linked to [sessionId], if it exists and is IN_PROGRESS.
   */
  private suspend fun advanceLinkedPipeline(
      sessionId: SessionId,
      transition: suspend (IssuePipeline) -> PipelineTransition,
  ) {
    val pipeline = issuePipelineStore.findBySessionId(sessionId) ?: return
    // The shadow (built-in) session is unobserved: it never advances pipeline state. Only the
    // primary session drives the pipeline, so a shadow session reaching a terminal state is a no-op
    // here — its result lives only on its own PR, closed manually.
    if (pipeline.sessionId != sessionId) return
    if (pipeline.state == IssuePipelineState.InProgress) transition(pipeline)
  }

  private fun parsePrNumber(
      prUrl: String,
  ): Int = prNumberRegex.find(prUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
