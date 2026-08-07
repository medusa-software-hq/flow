package software.medusa.flow.worker

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WrkAuthWedgeWatchdog_tests {
  @Test
  fun `fires onWedged once the failure streak reaches the threshold`() {
    var fixedNow = Instant.parse("2024-01-01T00:00:00Z")
    var wedgedElapsed: Duration? = null
    val watchdog =
        WrkAuthWedgeWatchdog(
            threshold = Duration.ofMinutes(10),
            now = { fixedNow },
            onWedged = { wedgedElapsed = it },
        )

    watchdog.recordFailure()
    fixedNow = fixedNow.plus(Duration.ofMinutes(5))
    watchdog.recordFailure()
    assertEquals(null, wedgedElapsed, "must not fire before the threshold elapses")

    fixedNow = fixedNow.plus(Duration.ofMinutes(6))
    watchdog.recordFailure()
    assertEquals(Duration.ofMinutes(11), wedgedElapsed)
  }

  @Test
  fun `a success in between resets the streak`() {
    var fixedNow = Instant.parse("2024-01-01T00:00:00Z")
    var wedged = false
    val watchdog =
        WrkAuthWedgeWatchdog(
            threshold = Duration.ofMinutes(10),
            now = { fixedNow },
            onWedged = { wedged = true },
        )

    watchdog.recordFailure()
    fixedNow = fixedNow.plus(Duration.ofMinutes(9))
    watchdog.recordSuccess()
    fixedNow = fixedNow.plus(Duration.ofMinutes(9))
    watchdog.recordFailure()

    assertFalse(wedged, "the reset streak shouldn't count the pre-success failure")
  }

  @Test
  fun `only fires once`() {
    var fixedNow = Instant.parse("2024-01-01T00:00:00Z")
    var wedgedCount = 0
    val watchdog =
        WrkAuthWedgeWatchdog(
            threshold = Duration.ofMinutes(10),
            now = { fixedNow },
            onWedged = { wedgedCount++ },
        )

    watchdog.recordFailure()
    fixedNow = fixedNow.plus(Duration.ofMinutes(11))
    watchdog.recordFailure()
    watchdog.recordFailure()

    assertTrue(wedgedCount == 1)
  }
}
