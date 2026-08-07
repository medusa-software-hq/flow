package software.medusa.flow.worker

import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/** In-memory [WrkApiClient] for tests: no network, no real control plane. */
class WrkFakeApiClient(
    private val queue: MutableList<Session> = mutableListOf(),
) : WrkApiClient {
  sealed interface RecordedCall {
    data class RegisterWorker(
        val workerId: String,
        val workerVersion: String,
        val imageDigest: String,
    ) : RecordedCall

    data class AppendSessionEvent(
        val sessionId: String,
        val kind: SessionEventKind,
        val message: String,
        val costUsd: Double? = null,
    ) : RecordedCall

    data class Heartbeat(val sessionId: String) : RecordedCall

    data class CompleteSession(val sessionId: String, val prUrl: String) : RecordedCall

    data class FailSession(
        val sessionId: String,
        val failureSummary: String,
        val workerDeath: Boolean = false,
    ) : RecordedCall

    data object InvalidateCredentials : RecordedCall
  }

  val recordedCalls: MutableList<RecordedCall> = mutableListOf()

  fun enqueue(session: Session) {
    queue.add(session)
  }

  /**
   * When set, every `claimNextJob` throws this instead of returning — lets tests drive the
   * control-plane-rejects-every-call paths (e.g. a wedged UNAUTHENTICATED credential).
   */
  var claimNextJobFailure: (() -> Exception)? = null

  override suspend fun claimNextSession(): Session? =
      if (queue.isEmpty()) null else queue.removeAt(0)

  override suspend fun claimNextJob(): List<Session> {
    claimNextJobFailure?.let { throw it() }
    if (queue.isEmpty()) return emptyList()
    // Drain every queued session sharing the head's job — the fake's stand-in for one job's set.
    val jobId = queue.first().jobId
    val job = queue.filter { it.jobId == jobId }
    queue.removeAll(job.toSet())
    return job
  }

  /**
   * When set, every `registerWorker` throws this instead of recording — mirrors
   * [claimNextJobFailure].
   */
  var registerWorkerFailure: (() -> Exception)? = null

  override suspend fun registerWorker(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
  ) {
    registerWorkerFailure?.let { throw it() }
    recordedCalls.add(RecordedCall.RegisterWorker(workerId, workerVersion, imageDigest))
  }

  override suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ) {
    recordedCalls.add(RecordedCall.AppendSessionEvent(sessionId, kind, message, costUsd))
  }

  /** When true, every heartbeat reports the session ABORTED — lets tests drive the abort path. */
  var abortOnHeartbeat: Boolean = false

  override suspend fun heartbeat(sessionId: String): Boolean {
    recordedCalls.add(RecordedCall.Heartbeat(sessionId))
    return abortOnHeartbeat
  }

  override suspend fun completeSession(
      sessionId: String,
      prUrl: String,
  ) {
    recordedCalls.add(RecordedCall.CompleteSession(sessionId, prUrl))
  }

  override suspend fun failSession(
      sessionId: String,
      failureSummary: String,
      workerDeath: Boolean,
  ) {
    recordedCalls.add(RecordedCall.FailSession(sessionId, failureSummary, workerDeath))
  }

  override suspend fun invalidateCredentials() {
    recordedCalls.add(RecordedCall.InvalidateCredentials)
  }
}
