package software.medusa.flow.worker

/**
 * Worker-specific environment configuration. `OPENROUTER_API_KEY` is validated separately, at CLI
 * startup, since every subcommand (not just `work`) needs it.
 *
 * Authentication isn't a config field here: [WrkGrpcApiClient] always uses Application Default
 * Credentials — short-lived credentials from impersonating `flow-worker` (see
 * `worker/scripts/get-worker-credentials.sh`). No downloaded long-lived key file, ever.
 */
data class WrkConfig(
    val apiUrl: String,
    val workerGitHubToken: String,
) {
  companion object {
    private const val apiUrlEnvVarName = "FLOW_API_URL"
    private const val workerGitHubTokenEnvVarName = "FLOW_WORKER_GITHUB_TOKEN"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkConfig {
      fun required(name: String): String =
          lookup(name)?.takeIf { it.isNotBlank() } ?: error("$name environment variable is not set")

      return WrkConfig(
          apiUrl = required(apiUrlEnvVarName),
          workerGitHubToken = required(workerGitHubTokenEnvVarName),
      )
    }
  }
}
