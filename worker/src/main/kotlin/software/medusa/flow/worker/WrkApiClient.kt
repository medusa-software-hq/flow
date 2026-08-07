package software.medusa.flow.worker

import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEventKind

/** Worker-facing control-plane client — thin wrapper over `WorkerService`. */
interface WrkApiClient {
  /** Absent return value means the queue is empty. */
  suspend fun claimNextSession(): Session?

  /**
   * Claims a whole job — all the sessions created together for one unit of work (a reconciled
   * issue's Claude + built-in sessions). The worker runs them in parallel. Empty means the queue is
   * empty.
   */
  suspend fun claimNextJob(): List<Session>

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

  /**
   * [workerDeath] marks this as the worker itself dying (a drain exceeding its deadline) rather
   * than a genuine engine/task failure — the control plane requeues those for a fresh attempt
   * (bounded retry) instead of terminating the session.
   */
  suspend fun failSession(
      sessionId: String,
      failureSummary: String,
      workerDeath: Boolean = false,
  )

  /**
   * Forces the client to mint a fresh credential on its next call, discarding whatever is cached.
   * Called after an UNAUTHENTICATED response, so a stuck/expired cached token can't just be
   * replayed forever waiting for a refresh that isn't coming — see [WrkGrpcApiClient]. No-op by
   * default: fakes and the auth-skipped test client hold no refreshable credential.
   */
  suspend fun invalidateCredentials() {}
}
