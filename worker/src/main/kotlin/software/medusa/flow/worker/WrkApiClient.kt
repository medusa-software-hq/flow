package software.medusa.flow.worker

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
  )

  suspend fun appendSessionEvent(
      sessionId: String,
      kind: SessionEventKind,
      message: String,
      costUsd: Double? = null,
  )

  /** Heartbeats the session; returns `true` if the control plane reports it was ABORTED (stop). */
  suspend fun heartbeat(sessionId: String): Boolean

  suspend fun completeSession(
      sessionId: String,
      prUrl: String,
  )

  suspend fun failSession(
      sessionId: String,
      failureSummary: String,
  )
}
