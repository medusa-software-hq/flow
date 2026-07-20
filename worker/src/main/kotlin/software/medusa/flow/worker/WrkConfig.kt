package software.medusa.flow.worker

import software.medusa.flow.v1.Engine

/**
 * Worker-specific environment configuration. `OPENROUTER_API_KEY` is validated separately, at CLI
 * startup, since every subcommand (not just `work`) needs it.
 *
 * Authentication isn't a config field here: [WrkGrpcApiClient] always uses Application Default
 * Credentials — short-lived credentials from impersonating `flow-worker` (see
 * `worker/scripts/get-worker-credentials.sh`). No downloaded long-lived key file, ever.
 *
 * [workerEngines] is the worker's engine capability set (M4): the ordered list of engines this
 * worker can run, declared on `ClaimNextSession` so the control plane only hands it sessions it can
 * execute. The **first** entry is the default used for sessions whose engine is `UNSPECIFIED`.
 */
data class WrkConfig(
    val apiUrl: String,
    val githubAppClientId: String,
    val githubAppPemContent: String,
    val workerEngines: List<Engine>,
) {
  companion object {
    private const val apiUrlEnvVarName = "FLOW_API_URL"
    private const val githubAppClientIdEnvVarName = "FLOW_WORKER_GITHUB_APP_CLIENT_ID"
    private const val githubAppPemContentEnvVarName = "FLOW_WORKER_GITHUB_APP_PEM"
    private const val workerEnginesEnvVarName = "FLOW_WORKER_ENGINES"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkConfig {
      fun required(name: String): String =
          lookup(name)?.takeIf { it.isNotBlank() } ?: error("$name environment variable is not set")

      return WrkConfig(
          apiUrl = required(apiUrlEnvVarName),
          githubAppClientId = required(githubAppClientIdEnvVarName),
          githubAppPemContent = required(githubAppPemContentEnvVarName),
          workerEngines = parseWorkerEngines(lookup),
      )
    }

    /**
     * Parses `FLOW_WORKER_ENGINES` — a comma-separated, ordered capability list (e.g. `claude` or
     * `builtin,claude`). Unset/blank keeps today's behavior: a builtin-only worker. The first entry
     * is the default engine for `UNSPECIFIED` sessions. Unknown tokens are a hard error — a
     * misconfigured worker should fail loudly at startup, not silently claim the wrong sessions.
     *
     * Exposed on its own so the composition root (`main.kt`) can decide which engine completers to
     * construct without needing the rest of [WrkConfig] (which requires `FLOW_API_URL` etc. that
     * the non-`work` subcommands don't set).
     */
    fun parseWorkerEngines(
        lookup: (String) -> String? = System::getenv,
    ): List<Engine> {
      val raw =
          lookup(workerEnginesEnvVarName)?.takeIf { it.isNotBlank() }
              ?: return listOf(Engine.ENGINE_BUILTIN)

      val engines =
          raw.split(",")
              .map { it.trim() }
              .filter { it.isNotEmpty() }
              .map { token ->
                when (token.lowercase()) {
                  "builtin" -> Engine.ENGINE_BUILTIN
                  "claude" -> Engine.ENGINE_CLAUDE
                  else ->
                      error(
                          "Unknown engine '$token' in $workerEnginesEnvVarName " +
                              "(expected: builtin, claude)",
                      )
                }
              }
              .distinct()

      return engines.ifEmpty { listOf(Engine.ENGINE_BUILTIN) }
    }
  }
}
