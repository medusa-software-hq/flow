package software.medusa.flow.systemtest

/**
 * The deployed environment the suite runs against, from the same env vars the old `smoke.sh`
 * consumed. Absent [fromEnvironment] (no `API_URL`) means "not configured": the tests skip rather
 * than fail (a laptop with no staging wiring, or a CI job that didn't set it), per story 01's
 * "gated on config being present".
 *
 * @property apiUrl the deployed API base URL, e.g.
 *   `https://api.flow-baseline-staging.medusa.software`. Doubles as the ID-token audience (see
 *   [StagingClients]).
 * @property webUrl the deployed SPA URL; defaults to [apiUrl] with the `api.` host prefix stripped.
 * @property workerSaEmail the worker service account to impersonate for the API identity. When set,
 *   the suite mints a worker-audience ID token by impersonating it (the gate path, matching
 *   `smoke.sh`); when null, it uses Application Default Credentials directly (a developer machine
 *   whose ADC is already a worker-class or otherwise-authorized identity).
 */
data class StagingConfig(
    val apiUrl: String,
    val webUrl: String,
    val workerSaEmail: String?,
) {
  companion object {
    private const val apiUrlEnvVarName = "API_URL"
    private const val webUrlEnvVarName = "WEB_URL"
    private const val workerSaEmailEnvVarName = "WORKER_SA_EMAIL"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): StagingConfig? {
      fun read(name: String): String? = lookup(name)?.takeIf { it.isNotBlank() }

      val apiUrl = read(apiUrlEnvVarName) ?: return null
      val webUrl = read(webUrlEnvVarName) ?: apiUrl.replace("https://api.", "https://")
      return StagingConfig(
          apiUrl = apiUrl,
          webUrl = webUrl,
          workerSaEmail = read(workerSaEmailEnvVarName),
      )
    }
  }
}
