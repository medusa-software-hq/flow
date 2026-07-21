package software.medusa.flow.cli

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

private fun b64url(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

/**
 * A JWT with the given payload JSON; header/signature are irrelevant to the payload-only reader.
 */
internal fun fakeJwt(payloadJson: String): String = "${b64url("{}")}.${b64url(payloadJson)}.sig"

class FlowOAuthTest {
  @Test
  fun `PKCE challenge is the base64url sha256 of the verifier`() {
    val verifier = generateCodeVerifier()
    val expected =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256")
                    .digest(verifier.toByteArray(Charsets.US_ASCII)),
            )
    assertEquals(expected, codeChallenge(verifier))
  }

  @Test
  fun `code verifier is url-safe and non-trivial`() {
    val verifier = generateCodeVerifier()
    assertTrue(verifier.length >= 43, "RFC 7636 requires >= 43 chars")
    assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
  }

  @Test
  fun `auth url carries the PKCE, client, and offline-consent params`() {
    val url = buildAuthUrl("client-x", "http://127.0.0.1:5555", "challenge-y", "state-z")
    assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
    for (part in
        listOf(
            "client_id=client-x",
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A5555",
            "response_type=code",
            "scope=openid+email",
            "code_challenge=challenge-y",
            "code_challenge_method=S256",
            "state=state-z",
            "access_type=offline",
            "prompt=consent",
        )) {
      assertTrue(part in url, "missing $part in $url")
    }
    // No hosted-domain hint unless one is configured.
    assertFalse("hd=" in url)
  }

  @Test
  fun `auth url includes the hosted-domain hint when configured`() {
    val url = buildAuthUrl("c", "http://127.0.0.1:1", "ch", "st", hostedDomain = "medusa.software")
    assertTrue("hd=medusa.software" in url)
  }

  @Test
  fun `parseQuery decodes pairs and tolerates junk`() {
    val parsed = parseQuery("code=abc%2F123&state=xyz&empty")
    assertEquals("abc/123", parsed["code"])
    assertEquals("xyz", parsed["state"])
    assertNull(parsed["empty"])
    assertTrue(parseQuery(null).isEmpty())
  }

  @Test
  fun `toFlowTokenSet reads expiry from the id token exp claim`() {
    val token = fakeJwt("""{"exp":1893456000}""")
    val set =
        toFlowTokenSet(
            FlowTokenEndpointResponse(idToken = token, refreshToken = "r", expiresIn = 3600),
        )
    assertEquals(1893456000L, set.expiresAtEpochSec)
    assertEquals("r", set.refreshToken)
  }

  @Test
  fun `token endpoint error response parses`() {
    val parsed =
        lenientJson.decodeFromString<FlowTokenEndpointResponse>(
            """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
        )
    assertEquals("invalid_grant", parsed.error)
    assertNull(parsed.idToken)
  }
}

class FlowJwtTest {
  @Test
  fun `reads email and exp from the payload`() {
    val token =
        fakeJwt("""{"email":"user@medusa.software","exp":1893456000,"hd":"medusa.software"}""")
    assertEquals("user@medusa.software", FlowJwt.email(token))
    assertEquals(1893456000L, FlowJwt.expiresAtEpochSec(token))
  }

  @Test
  fun `returns null on a non-jwt`() {
    assertNull(FlowJwt.email("nonsense"))
    assertNull(FlowJwt.expiresAtEpochSec("nonsense"))
  }
}
