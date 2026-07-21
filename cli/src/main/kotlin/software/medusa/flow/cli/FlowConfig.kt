package software.medusa.flow.cli

/**
 * Configuration for the read-only Flow API client and its human Google sign-in — all supplied by
 * the environment, nothing hardcoded. There is deliberately no baked-in default for the OAuth
 * client id or the API URL: the OAuth-client provisioning is being decided separately (see the
 * module's refactor notes), so an operator must point the CLI at a client id and API explicitly,
 * and gets a clear error if they haven't.
 *
 * Env vars:
 * - [apiUrlEnv] (`FLOW_API_URL`) — the deployed Flow API base URL. This doubles as the ID token's
 *   audience for the worker plane, but for the *user* sign-in the API validates `aud` against the
 *   OAuth client id, not this URL (see `GoogleIdTokenAuthDecorator`).
 * - [oauthClientIdEnv] (`FLOW_CLI_OAUTH_CLIENT_ID`) — the Google OAuth **Desktop** client id. The
 *   API accepts a user token whose `aud` equals this id.
 * - [oauthClientSecretEnv] (`FLOW_CLI_OAUTH_CLIENT_SECRET`) — the matching Desktop client secret.
 *   Google explicitly does not treat a Desktop client's secret as confidential, but the
 *   authorization-code exchange still requires it, so it's required to sign in.
 * - [allowedDomainEnv] (`FLOW_CLI_OAUTH_ALLOWED_DOMAIN`) — optional Google Workspace hosted domain.
 *   Used only as a sign-in hint (`hd`) to pre-select the right account in the browser; the API is
 *   the real enforcer of the domain, so leaving it unset changes nothing but the UX.
 */
data class FlowConfig(
    val apiUrl: String,
    val oauthClientId: String,
    val oauthClientSecret: String,
    val allowedDomain: String?,
) {
  companion object {
    const val apiUrlEnv = "FLOW_API_URL"
    const val oauthClientIdEnv = "FLOW_CLI_OAUTH_CLIENT_ID"
    const val oauthClientSecretEnv = "FLOW_CLI_OAUTH_CLIENT_SECRET"
    const val allowedDomainEnv = "FLOW_CLI_OAUTH_ALLOWED_DOMAIN"

    /**
     * Raised when a required config value is missing; the caller turns it into a clean CLI error.
     */
    class MissingException(message: String) : Exception(message)

    private fun required(lookup: (String) -> String?, name: String): String =
        lookup(name)?.takeIf { it.isNotBlank() }
            ?: throw MissingException(
                "$name is not set. Set it (and the other FLOW_* config vars) for your Flow " +
                    "deployment before running this command.",
            )

    /** Everything the read commands and `login`/`logout` need. */
    fun fromEnvironment(lookup: (String) -> String? = System::getenv): FlowConfig =
        FlowConfig(
            apiUrl = required(lookup, apiUrlEnv),
            oauthClientId = required(lookup, oauthClientIdEnv),
            oauthClientSecret = required(lookup, oauthClientSecretEnv),
            allowedDomain = lookup(allowedDomainEnv)?.takeIf { it.isNotBlank() },
        )

    /**
     * The subset needed to *read* (list) — a valid cached session already exists, so no client
     * secret is required unless the token needs a silent refresh. [FlowSession] resolves the secret
     * lazily only when it actually has to refresh, so a still-valid cached token lists with only
     * the API URL and client id present.
     */
    fun apiUrlFromEnvironment(lookup: (String) -> String? = System::getenv): String =
        required(lookup, apiUrlEnv)
  }
}
