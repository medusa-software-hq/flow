package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WrkConfig_tests {
  private val baseEnv =
      mapOf(
          "FLOW_API_URL" to "https://api.example",
          "FLOW_WORKER_GITHUB_APP_CLIENT_ID" to "cid",
          "FLOW_WORKER_GITHUB_APP_PEM" to "pem",
      )

  private fun configWith(extra: Map<String, String>): WrkConfig =
      WrkConfig.fromEnvironment(lookup = (baseEnv + extra)::get)

  @Test
  fun `FLOW_ENABLE_GPG_SIGNING parses true and false, case-insensitively, and defaults to false`() {
    assertFalse(WrkConfig.parseGpgSigningEnabled(null))
    assertFalse(WrkConfig.parseGpgSigningEnabled(""))
    assertFalse(WrkConfig.parseGpgSigningEnabled("  "))
    assertTrue(WrkConfig.parseGpgSigningEnabled("true"))
    assertTrue(WrkConfig.parseGpgSigningEnabled("TRUE"))
    assertTrue(WrkConfig.parseGpgSigningEnabled(" True "))
    assertFalse(WrkConfig.parseGpgSigningEnabled("false"))
    assertFalse(WrkConfig.parseGpgSigningEnabled("False"))
  }

  @Test
  fun `FLOW_ENABLE_GPG_SIGNING set to anything but true or false is a hard error`() {
    for (bad in listOf("yes", "1", "on", "enabled", "0", "no")) {
      val error = assertFailsWith<IllegalStateException> { WrkConfig.parseGpgSigningEnabled(bad) }
      assertTrue(error.message!!.contains("FLOW_ENABLE_GPG_SIGNING"), error.message)
    }
  }

  @Test
  fun `author email defaults when unset and is taken from the env when present`() {
    assertEquals(WrkConfig.defaultAuthorEmail, configWith(emptyMap()).authorEmail)
    assertEquals(
        "flow@medusa.software",
        configWith(mapOf("FLOW_AUTHOR_EMAIL" to "flow@medusa.software")).authorEmail,
    )
  }

  @Test
  fun `signing config threads through fromEnvironment`() {
    val off = configWith(emptyMap())
    assertFalse(off.gpgSigningEnabled)
    assertNull(off.gpgPrivateKey)

    val on =
        configWith(
            mapOf(
                "FLOW_ENABLE_GPG_SIGNING" to "true",
                "FLOW_WORKER_GPG_PRIVATE_KEY" to "-----BEGIN PGP PRIVATE KEY BLOCK-----",
            ),
        )
    assertTrue(on.gpgSigningEnabled)
    assertEquals("-----BEGIN PGP PRIVATE KEY BLOCK-----", on.gpgPrivateKey)
  }
}
