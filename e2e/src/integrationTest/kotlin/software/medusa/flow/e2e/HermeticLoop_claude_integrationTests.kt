package software.medusa.flow.e2e

import kotlin.test.Test

/**
 * The hermetic full loop, driven by the **claude** engine instead of the builtin AI one — the same
 * real control plane, shipped worker binary, real git, fake-GitHub stub, and `gradle` fixture, so
 * the claude engine earns the same per-PR regression net (see M4 story A7).
 *
 * Divergence from [HermeticLoop_integrationTests]: fan-out makes the pipeline's *primary* session
 * Claude in both, but here the worker start ([HermeticLoopHarness.startClaudeWorker]) runs that
 * primary on the **real** `claude` CLI (the builtin loop routes it to the builtin engine instead),
 * authenticated via `FLOW_CLAUDE_AUTH=personal`. That token (`CLAUDE_CODE_OAUTH_TOKEN`) and the
 * `claude` binary come from the environment running this test, by env inheritance — locally the
 * CLI's nested-session guard blocks it, so this exercises only in CI (workflow
 * `check-hermetic-loop-claude.yml`, `workflow_dispatch`-only to protect subscription quota).
 *
 * The `-claude` label suffix keeps this run's `LOOP_RESULT` flake accounting separable from the
 * builtin engine's in CI history.
 */
class HermeticLoop_claude_integrationTests {
  private val scenario =
      HermeticLoopScenario(
          startWorker = { it.startClaudeWorker() },
          labelSuffix = "-claude",
      )

  @Test
  fun `the gradle fixture goes from ready issue to merged, closed and unblocked, via the claude engine`() {
    scenario.runWithOneRetry(LoopFixture.gradle)
  }
}
