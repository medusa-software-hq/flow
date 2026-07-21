package software.medusa.flow.worker

import software.medusa.flow.v1.Engine
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
        val supportedEngines: List<Engine>,
    ) : RecordedCall

    data class AppendSessionEvent(
        val sessionId: String,
        val kind: SessionEventKind,
        val message: String,
        val costUsd: Double? = null,
    ) : RecordedCall

    data class Heartbeat(val sessionId: String) : RecordedCall

    data class CompleteSession(val sessionId: String, val prUrl: String) : RecordedCall

    data class FailSession(val sessionId: String, val failureSummary: String) : RecordedCall
  }

  val recordedCalls: MutableList<RecordedCall> = mutableListOf()

  fun enqueue(session: Session) {
    queue.add(session)
  }

  override suspend fun claimNextSession(): Session? =
      if (queue.isEmpty()) null else queue.removeAt(0)

  override suspend fun registerWorker(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
      supportedEngines: List<Engine>,
  ) {
    recordedCalls.add(
        RecordedCall.RegisterWorker(workerId, workerVersion, imageDigest, supportedEngines),
    )
  }

  override suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ) {
    recordedCalls.add(RecordedCall.AppendSessionEvent(sessionId, kind, message, costUsd))
  }

  override suspend fun heartbeat(sessionId: String) {
    recordedCalls.add(RecordedCall.Heartbeat(sessionId))
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
  ) {
    recordedCalls.add(RecordedCall.FailSession(sessionId, failureSummary))
  }
}
