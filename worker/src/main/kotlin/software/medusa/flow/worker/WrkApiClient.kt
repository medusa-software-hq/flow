package software.medusa.flow.worker

import software.medusa.flow.v1.Engine
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/** Worker-facing control-plane client — thin wrapper over `WorkerService`. */
interface WrkApiClient {
  /** Absent return value means the queue is empty. */
  suspend fun claimNextSession(): Session?

  /**
   * Registers (or refreshes) this worker's fleet-registry entry (M5), so the control plane knows it
   * is alive and what build it runs. Called periodically, independent of session activity, by
   * [WrkRegistrationLoop].
   */
  suspend fun registerWorker(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
      supportedEngines: List<Engine>,
  )

  suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
      costUsd: Double? = null,
  )

  suspend fun heartbeat(sessionId: String)

  suspend fun completeSession(
      sessionId: String,
      prUrl: String,
  )

  suspend fun failSession(
      sessionId: String,
      failureSummary: String,
  )
}
