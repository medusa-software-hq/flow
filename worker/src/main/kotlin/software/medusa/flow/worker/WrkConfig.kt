package software.medusa.flow.worker

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Worker-specific environment configuration. `OPENROUTER_API_KEY` is validated separately, at CLI
 * startup, since every subcommand (not just `work`) needs it.
 */
data class WrkConfig(
    val apiUrl: String,
    val workerSaKeyFile: Path,
    val workerGitHubToken: String,
) {
  companion object {
    private const val apiUrlEnvVarName = "FLOW_API_URL"
    private const val workerSaKeyFileEnvVarName = "FLOW_WORKER_SA_KEY_FILE"
    private const val workerGitHubTokenEnvVarName = "FLOW_WORKER_GITHUB_TOKEN"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkConfig {
      fun required(name: String): String =
          lookup(name)?.takeIf { it.isNotBlank() } ?: error("$name environment variable is not set")

      return WrkConfig(
          apiUrl = required(apiUrlEnvVarName),
          workerSaKeyFile = Paths.get(required(workerSaKeyFileEnvVarName)),
          workerGitHubToken = required(workerGitHubTokenEnvVarName),
      )
    }
  }
}
