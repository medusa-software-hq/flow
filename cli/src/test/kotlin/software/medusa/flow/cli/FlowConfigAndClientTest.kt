package software.medusa.flow.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FlowConfigTest {
  private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { map[it] }
  }

  @Test
  fun `env vars override every value`() {
    val config =
        FlowConfig.fromEnvironment(
            envOf(
                FlowConfig.apiUrlEnv to "https://api.flow.example",
                FlowConfig.oauthClientIdEnv to "client-123.apps.googleusercontent.com",
                FlowConfig.oauthClientSecretEnv to "secret-xyz",
                FlowConfig.allowedDomainEnv to "example.com",
            ),
        )
    assertEquals("https://api.flow.example", config.apiUrl)
    assertEquals("client-123.apps.googleusercontent.com", config.oauthClientId)
    assertEquals("secret-xyz", config.oauthClientSecret)
    assertEquals("example.com", config.allowedDomain)
  }

  @Test
  fun `only the secret is needed - everything else defaults to the prod deployment`() {
    val config = FlowConfig.fromEnvironment(envOf(FlowConfig.oauthClientSecretEnv to "secret-xyz"))

    assertEquals("https://api.flow-baseline.medusa.software", config.apiUrl)
    assertEquals(true, config.oauthClientId.endsWith(".apps.googleusercontent.com"))
    assertEquals("secret-xyz", config.oauthClientSecret)
    assertEquals("medusa.software", config.allowedDomain)
  }

  @Test
  fun `a missing client secret with none baked is a clear error naming the env var`() {
    // A normal test run bakes no secret into the resource, so with no env secret this must fail
    // cleanly (a published build bakes it; a local build sets the env var).
    val error =
        assertFailsWith<FlowConfig.Companion.MissingException> {
          FlowConfig.fromEnvironment(envOf())
        }
    assertEquals(true, error.message!!.contains(FlowConfig.oauthClientSecretEnv))
  }

  @Test
  fun `apiUrlFromEnvironment defaults to the prod URL and honours an override`() {
    assertEquals(
        "https://api.flow-baseline.medusa.software",
        FlowConfig.apiUrlFromEnvironment(envOf()),
    )
    assertEquals(
        "https://api.flow.example",
        FlowConfig.apiUrlFromEnvironment(envOf(FlowConfig.apiUrlEnv to "https://api.flow.example")),
    )
  }
}

class FlowApiClientTest {
  @Test
  fun `bearer header value prefixes the id token`() {
    assertEquals("Bearer tok-abc", FlowApiClient.bearerHeaderValue("tok-abc"))
  }
}
