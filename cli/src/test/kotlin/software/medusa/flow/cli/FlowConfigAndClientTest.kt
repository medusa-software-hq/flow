package software.medusa.flow.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FlowConfigTest {
  private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { map[it] }
  }

  @Test
  fun `fromEnvironment reads all values, with allowed-domain optional`() {
    val config =
        FlowConfig.fromEnvironment(
            envOf(
                FlowConfig.apiUrlEnv to "https://api.flow.example",
                FlowConfig.oauthClientIdEnv to "client-123.apps.googleusercontent.com",
                FlowConfig.oauthClientSecretEnv to "secret-xyz",
            ),
        )
    assertEquals("https://api.flow.example", config.apiUrl)
    assertEquals("client-123.apps.googleusercontent.com", config.oauthClientId)
    assertEquals("secret-xyz", config.oauthClientSecret)
    assertNull(config.allowedDomain)
  }

  @Test
  fun `a missing required value is a clear error naming the env var`() {
    val error =
        assertFailsWith<FlowConfig.Companion.MissingException> {
          FlowConfig.fromEnvironment(envOf(FlowConfig.apiUrlEnv to "https://api.flow.example"))
        }
    assertEquals(true, error.message!!.contains(FlowConfig.oauthClientIdEnv))
  }

  @Test
  fun `blank values are treated as unset`() {
    assertFailsWith<FlowConfig.Companion.MissingException> {
      FlowConfig.apiUrlFromEnvironment(envOf(FlowConfig.apiUrlEnv to "   "))
    }
  }
}

class FlowApiClientTest {
  @Test
  fun `bearer header value prefixes the id token`() {
    assertEquals("Bearer tok-abc", FlowApiClient.bearerHeaderValue("tok-abc"))
  }
}
