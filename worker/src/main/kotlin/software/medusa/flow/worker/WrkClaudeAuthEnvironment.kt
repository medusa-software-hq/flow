package software.medusa.flow.worker

/**
 * Builds the auth-rung environment for the claude engine's subprocess (M4).
 *
 * The `claude` binary runs with a **replace-not-inherit** environment (see
 * `HrsProcessClaudeProcess`): the child gets exactly this map and nothing else. That means two
 * things have to be in it:
 * - the auth vars for the active rung, selected by `FLOW_CLAUDE_AUTH` (default `personal`); and
 * - `PATH` + `HOME` from the worker's own environment — the `claude` node binary can't launch
 *   without them (it resolves `node`/helpers via `PATH` and its own state via `HOME`). Omitting
 *   these is A3's explicitly-flagged gap; A6 fills it.
 *
 * The rungs (02-auth-and-modes.md, 2026-07-18 pivot — `personal` is the default everywhere):
 * - `personal` → `CLAUDE_CODE_OAUTH_TOKEN` (from `claude setup-token`), read from the worker's env.
 * - `api-key` → `ANTHROPIC_API_KEY`, read from the worker's env.
 * - `vertex` → `CLAUDE_CODE_USE_VERTEX=1`; Google ADC is supplied by the ambient environment (the
 *   worker SA), so no secret var is injected here.
 */
object WrkClaudeAuthEnvironment {
  private const val authModeEnvVarName = "FLOW_CLAUDE_AUTH"

  const val oauthTokenEnvVarName = "CLAUDE_CODE_OAUTH_TOKEN"
  const val apiKeyEnvVarName = "ANTHROPIC_API_KEY"
  const val useVertexEnvVarName = "CLAUDE_CODE_USE_VERTEX"

  fun build(
      lookup: (String) -> String? = System::getenv,
  ): Map<String, String> {
    fun required(name: String): String =
        lookup(name)?.takeIf { it.isNotBlank() }
            ?: error(
                "$name environment variable is not set, required by FLOW_CLAUDE_AUTH=${authMode(lookup)}.",
            )

    val rungEnvironment =
        when (val mode = authMode(lookup)) {
          "personal" -> mapOf(oauthTokenEnvVarName to required(oauthTokenEnvVarName))
          "api-key" -> mapOf(apiKeyEnvVarName to required(apiKeyEnvVarName))
          "vertex" -> mapOf(useVertexEnvVarName to "1")
          else ->
              error("Unknown $authModeEnvVarName '$mode' (expected: personal, api-key, vertex).")
        }

    // The minimal host vars the node binary needs; replace-not-inherit means nothing else leaks in.
    val passthrough = buildMap {
      lookup("PATH")?.let { put("PATH", it) }
      lookup("HOME")?.let { put("HOME", it) }
    }

    return passthrough + rungEnvironment
  }

  private fun authMode(
      lookup: (String) -> String?,
  ): String = lookup(authModeEnvVarName)?.takeIf { it.isNotBlank() }?.lowercase() ?: "personal"
}
