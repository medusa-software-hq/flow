package software.medusa.flow.worker

/**
 * Worker-specific environment configuration. `OPENROUTER_API_KEY` is validated separately, at CLI
 * startup, since every subcommand (not just `work`) needs it.
 *
 * Authentication isn't a config field here: [WrkGrpcApiClient] always uses Application Default
 * Credentials — short-lived credentials from impersonating `flow-worker` (see
 * `worker/scripts/get-worker-credentials.sh`). No downloaded long-lived key file, ever.
 *
 * There is no engine-capability field: workers are uniform (every worker runs every engine), so a
 * worker advertises nothing and its resolver holds a completer for each engine statically.
 */
data class WrkConfig(
    val apiUrl: String,
    val githubAppClientId: String,
    val githubAppPemContent: String,
) {
  companion object {
    private const val apiUrlEnvVarName = "FLOW_API_URL"
    private const val githubAppClientIdEnvVarName = "FLOW_WORKER_GITHUB_APP_CLIENT_ID"
    private const val githubAppPemContentEnvVarName = "FLOW_WORKER_GITHUB_APP_PEM"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkConfig {
      fun required(name: String): String =
          lookup(name)?.takeIf { it.isNotBlank() } ?: error("$name environment variable is not set")

      return WrkConfig(
          apiUrl = required(apiUrlEnvVarName),
          githubAppClientId = required(githubAppClientIdEnvVarName),
          githubAppPemContent = required(githubAppPemContentEnvVarName),
      )
    }
  }
}
