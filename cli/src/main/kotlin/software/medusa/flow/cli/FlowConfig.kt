package software.medusa.flow.cli

import java.util.Properties

/**
 * Configuration for the read-only Flow API client and its human Google sign-in.
 *
 * The defaults target the **production** Flow deployment, so `flow login` / `flow sessions` work
 * out of the box on a published build. The API URL and OAuth client id are public and live in
 * source; the Desktop client **secret** is NOT in source — it is baked into the published fat jar
 * at build time from an Actions secret (Publish CLI passes `-PflowCliOauthClientSecret`), so a
 * released `flow` has it while the repo does not. A local build bakes nothing and falls back to the
 * env var.
 *
 * Everything is overridable by an env var to point at another deployment (staging, local). The API
 * URL and the client id/secret are a *matched set* — a client id is only accepted by its own
 * environment's API — so override the trio together when switching environments.
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

    // Production defaults — the "flow CLI" Desktop OAuth client in project ms-flow-b71f4835.
    // Public,
    // safe in source; kept in sync with infra/common's prod `cli_oauth_client_id` (the API's
    // accepted CLI audience). The matching non-confidential Desktop secret is baked at publish, not
    // here (see below).
    private const val defaultApiUrl = "https://api.flow-baseline.medusa.software"
    private const val defaultOauthClientId =
        "205101361240-ck8vbai6stemci1omufc1p5vqmup5562.apps.googleusercontent.com"
    private const val defaultAllowedDomain = "medusa.software"

    // Values baked into the published fat jar at build time (see cli/build.gradle.kts). Absent on a
    // local build, where the env vars take over.
    private val baked: Properties =
        Properties().apply {
          FlowConfig::class.java.getResourceAsStream("/flow-cli-build.properties")?.use { load(it) }
        }

    private fun bakedProperty(key: String): String? = baked.getProperty(key)?.ifBlank { null }

    private fun envOr(
        lookup: (String) -> String?,
        name: String,
        fallback: String,
    ): String = lookup(name)?.takeIf { it.isNotBlank() } ?: fallback

    /**
     * Raised when the client secret can't be resolved; the caller turns it into a clean CLI error.
     */
    class MissingException(message: String) : Exception(message)

    /** Everything the read commands and `login`/`logout` need. */
    fun fromEnvironment(lookup: (String) -> String? = System::getenv): FlowConfig {
      // env override > baked-at-build > (none → a clean error). The client secret is the only value
      // that isn't a public default: a published build has it baked; a local build needs the env
      // var.
      val secret =
          lookup(oauthClientSecretEnv)?.takeIf { it.isNotBlank() }
              ?: bakedProperty("oauthClientSecret")
              ?: throw MissingException(
                  "No OAuth client secret available. A published `flow` build bakes it in; for a " +
                      "local build, set $oauthClientSecretEnv.",
              )

      return FlowConfig(
          apiUrl = envOr(lookup, apiUrlEnv, bakedProperty("apiBaseUrl") ?: defaultApiUrl),
          oauthClientId = envOr(lookup, oauthClientIdEnv, defaultOauthClientId),
          oauthClientSecret = secret,
          allowedDomain = envOr(lookup, allowedDomainEnv, defaultAllowedDomain),
      )
    }

    /**
     * The API URL alone — the only thing a *read* (list) needs when a still-valid cached token
     * exists ([FlowSession] resolves the client secret lazily, only on a silent refresh).
     */
    fun apiUrlFromEnvironment(lookup: (String) -> String? = System::getenv): String =
        envOr(lookup, apiUrlEnv, bakedProperty("apiBaseUrl") ?: defaultApiUrl)
  }
}
