package software.medusa.flow.worker

import java.time.Duration
import java.time.Instant

/**
 * Tracks how long UNAUTHENTICATED has been continuous across the poll and registration loops — they
 * share one identity token (see [WrkGrpcApiClient]), so either can notice a wedge first — and fires
 * [onWedged] once that streak reaches [threshold] without a single successful call in between.
 *
 * Both loops already force a fresh token on every UNAUTHENTICATED (`invalidateCredentials`), so
 * reaching the threshold means that isn't working: the underlying identity source itself is stuck,
 * not just the cache. Retrying a doomed token forever has, in practice, only ever been fixed by a
 * human restarting the process — so past the threshold this stops hoping and says so, letting the
 * caller halt and let the supervisor respawn with a clean process instead of blackholing silently.
 */
class WrkAuthWedgeWatchdog(
    private val threshold: Duration = Duration.ofMinutes(10),
    private val now: () -> Instant = Instant::now,
    private val onWedged: (Duration) -> Unit,
) {
  private val lock = Any()
  private var firstFailureAt: Instant? = null
  private var fired = false

  /** Call on every UNAUTHENTICATED failure. */
  fun recordFailure() {
    synchronized(lock) {
      if (fired) return
      val first = firstFailureAt ?: now().also { firstFailureAt = it }
      val elapsed = Duration.between(first, now())
      if (elapsed >= threshold) {
        fired = true
        onWedged(elapsed)
      }
    }
  }

  /** Call on any successful authenticated call — clears the streak. */
  fun recordSuccess() {
    synchronized(lock) { firstFailureAt = null }
  }
}
