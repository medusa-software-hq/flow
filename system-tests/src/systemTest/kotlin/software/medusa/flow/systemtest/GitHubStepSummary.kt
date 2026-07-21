package software.medusa.flow.systemtest

import java.io.File

/**
 * Appends a Markdown line to the GitHub Actions step summary (`$GITHUB_STEP_SUMMARY`) when running
 * in a workflow, so version-skew and liveness facts land in the gate's rendered summary — not just
 * buried in the test log. A no-op off CI (the env var is absent), and never fatal: a failure to
 * write the summary must not fail a test.
 */
object GitHubStepSummary {
  private const val envVarName = "GITHUB_STEP_SUMMARY"

  fun append(
      line: String,
      lookupEnv: (String) -> String? = System::getenv,
  ) {
    val path = lookupEnv(envVarName)?.takeIf { it.isNotBlank() } ?: return
    runCatching { File(path).appendText(line.trimEnd() + "\n") }
  }
}
