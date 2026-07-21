package software.medusa.flow.systemtest

import java.time.Duration
import kotlinx.coroutines.delay

/**
 * Poll [probe] until it returns a non-null value, or [timeout] elapses. Returns the first non-null
 * result. On timeout throws [AssertionError] naming [description] and the elapsed time — the
 * awaitility-style helper the module needs (there is no shared one), keeping the CLAUDE.md
 * discipline that a wait failure says *what* it was waiting for and *how long* it waited.
 *
 * [onPoll] is invoked with a short status each attempt (default: silent), so a long loop-tier wait
 * can narrate progress into the test log.
 */
suspend fun <T> awaitUntil(
    description: String,
    timeout: Duration,
    pollInterval: Duration = Duration.ofSeconds(5),
    onPoll: (String) -> Unit = {},
    probe: suspend () -> T?,
): T {
  val startNanos = System.nanoTime()
  val deadlineNanos = startNanos + timeout.toNanos()

  var attempt = 0
  while (true) {
    attempt++
    val result = probe()
    if (result != null) {
      onPoll("$description: satisfied after ${elapsed(startNanos)} (attempt $attempt)")
      return result
    }

    if (System.nanoTime() >= deadlineNanos) {
      throw AssertionError(
          "Timed out after ${elapsed(startNanos)} waiting for: $description " +
              "(${attempt} attempts, ${pollInterval.toSeconds()}s interval)",
      )
    }

    onPoll("$description: not yet (attempt $attempt, ${elapsed(startNanos)} elapsed)")
    delay(pollInterval.toMillis())
  }
}

private fun elapsed(
    startNanos: Long,
): String = "${(System.nanoTime() - startNanos) / 1_000_000_000}s"
