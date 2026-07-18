package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WrkClaudeAuthEnvironment_tests {
  private fun envOf(vararg entries: Pair<String, String>): (String) -> String? {
    val map = entries.toMap()
    return { name -> map[name] }
  }

  @Test
  fun `personal rung injects the oauth token plus PATH and HOME`() {
    val env =
        WrkClaudeAuthEnvironment.build(
            lookup =
                envOf(
                    "CLAUDE_CODE_OAUTH_TOKEN" to "sk-oauth-abc",
                    "PATH" to "/usr/bin:/bin",
                    "HOME" to "/home/worker",
                ),
        )

    assertEquals("sk-oauth-abc", env["CLAUDE_CODE_OAUTH_TOKEN"])
    assertEquals("/usr/bin:/bin", env["PATH"])
    assertEquals("/home/worker", env["HOME"])
    // Otherwise minimal: no API-key / Vertex vars leak into the personal rung.
    assertEquals(setOf("CLAUDE_CODE_OAUTH_TOKEN", "PATH", "HOME"), env.keys)
  }

  @Test
  fun `personal is the default rung when FLOW_CLAUDE_AUTH is unset`() {
    val env =
        WrkClaudeAuthEnvironment.build(
            lookup = envOf("CLAUDE_CODE_OAUTH_TOKEN" to "sk-oauth-abc", "PATH" to "/bin"),
        )

    assertTrue(env.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
  }

  @Test
  fun `api-key rung injects ANTHROPIC_API_KEY plus PATH and HOME`() {
    val env =
        WrkClaudeAuthEnvironment.build(
            lookup =
                envOf(
                    "FLOW_CLAUDE_AUTH" to "api-key",
                    "ANTHROPIC_API_KEY" to "sk-ant-xyz",
                    "PATH" to "/usr/bin",
                    "HOME" to "/home/worker",
                ),
        )

    assertEquals("sk-ant-xyz", env["ANTHROPIC_API_KEY"])
    assertEquals(setOf("ANTHROPIC_API_KEY", "PATH", "HOME"), env.keys)
  }

  @Test
  fun `vertex rung sets CLAUDE_CODE_USE_VERTEX with no secret var`() {
    val env =
        WrkClaudeAuthEnvironment.build(
            lookup = envOf("FLOW_CLAUDE_AUTH" to "vertex", "PATH" to "/usr/bin", "HOME" to "/home"),
        )

    assertEquals("1", env["CLAUDE_CODE_USE_VERTEX"])
    assertEquals(setOf("CLAUDE_CODE_USE_VERTEX", "PATH", "HOME"), env.keys)
  }

  @Test
  fun `personal rung fails when the oauth token is missing`() {
    assertFailsWith<IllegalStateException> {
      WrkClaudeAuthEnvironment.build(lookup = envOf("PATH" to "/usr/bin"))
    }
  }

  @Test
  fun `an unknown FLOW_CLAUDE_AUTH mode is rejected`() {
    assertFailsWith<IllegalStateException> {
      WrkClaudeAuthEnvironment.build(lookup = envOf("FLOW_CLAUDE_AUTH" to "sso"))
    }
  }
}
