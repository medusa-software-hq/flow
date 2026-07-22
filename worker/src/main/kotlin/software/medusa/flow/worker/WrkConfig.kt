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
 *
 * Commit-signing config ([authorEmail], [gpgSigningEnabled], [gpgPrivateKey]) is resolved per env
 * from the worker's profile (Terraform-provisioned secrets). Signing is off by default; when it's
 * on, the publisher re-signs each commit with the [gpgPrivateKey] via the `git` CLI — the in-house
 * git library can't sign yet.
 */
data class WrkConfig(
    val apiUrl: String,
    val githubAppClientId: String,
    val githubAppPemContent: String,
    val authorEmail: String,
    val gpgSigningEnabled: Boolean,
    val gpgPrivateKey: String?,
) {
  companion object {
    private const val apiUrlEnvVarName = "FLOW_API_URL"
    private const val githubAppClientIdEnvVarName = "FLOW_WORKER_GITHUB_APP_CLIENT_ID"
    private const val githubAppPemContentEnvVarName = "FLOW_WORKER_GITHUB_APP_PEM"
    private const val authorEmailEnvVarName = "FLOW_AUTHOR_EMAIL"
    private const val enableGpgSigningEnvVarName = "FLOW_ENABLE_GPG_SIGNING"
    private const val gpgPrivateKeyEnvVarName = "FLOW_WORKER_GPG_PRIVATE_KEY"

    /** The commit author email when [authorEmailEnvVarName] is unset — the historical default. */
    const val defaultAuthorEmail = "flow-worker@users.noreply.github.com"

    fun fromEnvironment(
        lookup: (String) -> String? = System::getenv,
    ): WrkConfig {
      fun required(name: String): String =
          lookup(name)?.takeIf { it.isNotBlank() } ?: error("$name environment variable is not set")

      fun optional(name: String): String? = lookup(name)?.takeIf { it.isNotBlank() }

      return WrkConfig(
          apiUrl = required(apiUrlEnvVarName),
          githubAppClientId = required(githubAppClientIdEnvVarName),
          githubAppPemContent = required(githubAppPemContentEnvVarName),
          authorEmail = optional(authorEmailEnvVarName) ?: defaultAuthorEmail,
          gpgSigningEnabled = parseGpgSigningEnabled(lookup(enableGpgSigningEnvVarName)),
          gpgPrivateKey = optional(gpgPrivateKeyEnvVarName),
      )
    }

    /**
     * `FLOW_ENABLE_GPG_SIGNING` → boolean. Unset/blank is `false`; otherwise it must be exactly
     * `true` or `false` (case-insensitive). Any other value is a hard misconfiguration — we fail
     * fast rather than silently treating, say, `yes` or `1` as off.
     */
    internal fun parseGpgSigningEnabled(raw: String?): Boolean =
        when (val value = raw?.trim()?.lowercase()) {
          null,
          "" -> false
          "true" -> true
          "false" -> false
          else ->
              error(
                  "$enableGpgSigningEnvVarName must be 'true' or 'false' (or unset), got: '$value'",
              )
        }
  }
}
