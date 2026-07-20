package software.medusa.flow.worker

import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/** Worker-facing control-plane client — thin wrapper over `WorkerService`. */
interface WrkApiClient {
  /** Absent return value means the queue is empty. */
  suspend fun claimNextSession(): Session?

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
