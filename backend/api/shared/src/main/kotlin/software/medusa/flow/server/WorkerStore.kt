package software.medusa.flow.server

import kotlin.time.Instant

/**
 * A registered worker's last-known state (M5). Toolchain- and transport-agnostic (no proto types),
 * following the [SessionStore] domain-model convention.
 */
data class RegisteredWorker(
    val workerId: String,
    val workerVersion: String,
    val imageDigest: String,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
)

/**
 * Storage for the worker fleet registry (M5), following the [SessionStore] pattern (a store
 * interface with a Postgres impl and an in-memory fake).
 *
 * A worker [register]s itself periodically — independent of session activity — so [list] answers
 * "which workers are alive, and what build does each run?" purely from registration recency. This
 * powers the system-test gate's worker-liveness preflight (a distinct "staging worker down" when no
 * worker has registered recently) and its version-skew surfacing (story 04).
 */
interface WorkerStore {
  /**
   * Upserts the worker's registration, keyed on [workerId]: inserts on first sight (stamping both
   * `first_seen_at` and `last_seen_at` to now), and on every later call refreshes the mutable
   * fields and `last_seen_at` while preserving `first_seen_at`.
   */
  suspend fun register(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
  )

  /** All registered workers, freshest (most recently seen) first. */
  suspend fun list(): List<RegisteredWorker>
}
