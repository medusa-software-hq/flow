package software.medusa.flow.e2e

import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName

/**
 * A project the loop test can drive end to end, plus the task to ask a model for and the way to
 * tell whether it landed.
 *
 * Parameterized by toolchain from the start even though only [gradle] exists today: the loop itself
 * is toolchain-agnostic (it goes through `UnpProjectConnection` and never branches on Gradle vs
 * Node), and each toolchain already has its own integration tests, so a second full loop mostly
 * re-runs the same loop while doubling the AI-flake surface this milestone budgets for. Adding one
 * later is a resource directory plus a matrix entry; retrofitting the parameter would not be.
 */
data class LoopFixture(
    /** Names the CI job and the reported flake line. */
    val name: String,
    /** Test-resource directory seeded into the bare repo as the project's initial commit. */
    val resourcePath: UfsLiteralAbsolutePath,
    /** The issue title — the headline the model is asked to act on. */
    val issueTitle: String,
    /**
     * The issue body: the task itself. Deliberately explicit; this test measures the loop, not the
     * model.
     */
    val issueBody: String,
    /** Path (inside the repo) whose content proves the task was done. */
    val provingFilePath: String,
    /** Substring that must appear in [provingFilePath] on the pushed branch. */
    val provingContent: String,
) {
  companion object {
    private fun resource(
        name: String,
    ): UfsLiteralAbsolutePath =
        UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal(name))

    /**
     * A dependency-free Java project: `Greeter.GREETING` plus a `GreetingCheck` that asserts it, so
     * changing the greeting means a coherent two-file edit and the gate (compile + verifyGreeting)
     * genuinely distinguishes done from half-done. Chosen for triviality — a ~99% pass rate keeps
     * this test measuring the loop rather than the model.
     */
    val gradle =
        LoopFixture(
            name = "gradle",
            resourcePath = resource("gradle-project"),
            issueTitle = "Change the greeting to Goodbye",
            issueBody =
                """
                The application greets with `Hello`. It should greet with `Goodbye` instead.

                Update `Greeter.GREETING` to `Goodbye`, and update `GreetingCheck.EXPECTED_GREETING`
                to match so that the `verifyGreeting` check still passes.
                """
                    .trimIndent(),
            provingFilePath = "src/main/java/com/example/Greeter.java",
            provingContent = "Goodbye",
        )

    /** Every fixture the loop runs against. One entry per CI matrix job. */
    val all = listOf(gradle)
  }
}
