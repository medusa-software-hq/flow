package software.medusa.flow.e2e

import kotlin.test.Test

/**
 * The milestone's centerpiece: the M1 demo (and the core M2 cycle) as an automated test — real
 * control plane, real shipped worker binary, real git, real (builtin AI) engine on cheap models.
 * Only GitHub is a local stub.
 *
 * The loop itself, its assertions, and the one-retry flake budget live in [HermeticLoopScenario];
 * this class just points it at the builtin worker. The parallel
 * [HermeticLoop_claude_integrationTests] points the same scenario at the claude engine.
 */
class HermeticLoop_integrationTests {
  private val scenario = HermeticLoopScenario(startWorker = { it.startWorker() })

  @Test
  fun `the gradle fixture goes from ready issue to merged, closed and unblocked`() {
    scenario.runWithOneRetry(LoopFixture.gradle)
  }
}
