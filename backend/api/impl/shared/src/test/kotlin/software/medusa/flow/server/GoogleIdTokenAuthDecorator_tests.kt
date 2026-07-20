package software.medusa.flow.server

import com.nimbusds.jwt.JWTClaimsSet
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [GoogleIdTokenAuthDecorator.resolveAuthorizedEmail] — the issuer/audience/hosted-domain
 * branching that decides whether a caller is a recognized user or worker. Doesn't exercise
 * signature/JWKS verification (that needs live network access to Google); that path is proven by a
 * real deployed run instead.
 */
class GoogleIdTokenAuthDecorator_tests {
  companion object {
    private const val userTokenAudience = "user-client-id.apps.googleusercontent.com"
    private const val allowedDomain = "medusa.software"
    private const val workerTokenAudience = "https://api.example.com/"
    private const val googleIssuer = "https://accounts.google.com"
  }

  private val decorator =
      GoogleIdTokenAuthDecorator(
          userTokenAudience = userTokenAudience,
          allowedDomain = allowedDomain,
          workerTokenAudience = workerTokenAudience,
      )

  private fun claims(
      issuer: String = googleIssuer,
      audience: String,
      email: String = "someone@example.com",
      hd: String? = null,
  ): JWTClaimsSet =
      JWTClaimsSet.Builder()
          .issuer(issuer)
          .audience(audience)
          .claim("email", email)
          .apply { hd?.let { claim("hd", it) } }
          .expirationTime(Date(Long.MAX_VALUE))
          .build()

  @Test
  fun `a user token with matching audience and hosted domain is authorized`() {
    val result =
        decorator.resolveAuthorizedEmail(
            claims(
                audience = userTokenAudience,
                email = "person@medusa.software",
                hd = allowedDomain,
            ),
        )

    assertEquals("person@medusa.software", result)
  }

  @Test
  fun `a user token with a wrong hosted domain is rejected`() {
    val result =
        decorator.resolveAuthorizedEmail(
            claims(audience = userTokenAudience, hd = "not-medusa.software"),
        )

    assertNull(result)
  }

  @Test
  fun `a user token with no hosted domain at all is rejected`() {
    val result = decorator.resolveAuthorizedEmail(claims(audience = userTokenAudience, hd = null))

    assertNull(result)
  }

  @Test
  fun `a worker token with matching audience and no hosted domain is authorized`() {
    val result =
        decorator.resolveAuthorizedEmail(
            claims(
                audience = workerTokenAudience,
                email = "flow-worker@project.iam.gserviceaccount.com",
                hd = null,
            ),
        )

    assertEquals("flow-worker@project.iam.gserviceaccount.com", result)
  }

  @Test
  fun `a worker token whose audience omits the configured trailing slash is authorized`() {
    // Configured workerTokenAudience is "https://api.example.com/"; the token's aud has no slash.
    val result =
        decorator.resolveAuthorizedEmail(
            claims(
                audience = "https://api.example.com",
                email = "flow-worker@project.iam.gserviceaccount.com",
                hd = null,
            ),
        )

    assertEquals("flow-worker@project.iam.gserviceaccount.com", result)
  }

  @Test
  fun `a token matching neither audience is rejected`() {
    val result = decorator.resolveAuthorizedEmail(claims(audience = "https://someone-elses-app/"))

    assertNull(result)
  }

  @Test
  fun `a token from an unrecognized issuer is rejected`() {
    val result =
        decorator.resolveAuthorizedEmail(
            claims(issuer = "https://not-google.example.com", audience = workerTokenAudience),
        )

    assertNull(result)
  }
}
