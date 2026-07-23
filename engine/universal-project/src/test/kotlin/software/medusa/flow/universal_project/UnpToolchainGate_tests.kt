package software.medusa.flow.universal_project

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

class UnpToolchainGate_tests {
  /**
   * Launches [launches] coroutines that each hold [toolchain]'s gate across a suspension, and
   * returns the greatest number observed running inside the gate at once — i.e. the effective
   * concurrency limit. Distinct toolchain keys per test avoid contending on the process singleton.
   */
  private suspend fun observedMaxConcurrency(
      toolchain: String,
      launches: Int,
  ): Int {
    val current = AtomicInteger(0)
    val maxObserved = AtomicInteger(0)

    coroutineScope {
      repeat(launches) {
        launch {
          UnpToolchainGate.gated(toolchain) {
            val now = current.incrementAndGet()
            maxObserved.updateAndGet { seen -> max(seen, now) }
            delay(20)
            current.decrementAndGet()
          }
        }
      }
    }

    return maxObserved.get()
  }

  @Test
  fun `gradle is serialized to one build at a time by default`() = runTest {
    assertEquals(expected = 1, actual = observedMaxConcurrency("gradle", launches = 5))
  }

  @Test
  fun `nodejs is bounded to its permit count by default`() = runTest {
    assertEquals(expected = 4, actual = observedMaxConcurrency("nodejs", launches = 8))
  }

  @Test
  fun `an unknown toolchain runs ungated`() = runTest {
    // No configured limit for this key: every coroutine runs at once, nothing is gated.
    assertEquals(expected = 6, actual = observedMaxConcurrency("no-such-toolchain", launches = 6))
  }
}
