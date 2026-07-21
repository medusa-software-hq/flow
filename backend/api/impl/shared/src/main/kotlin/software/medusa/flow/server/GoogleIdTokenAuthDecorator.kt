package software.medusa.flow.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import java.net.URI

private const val httpAuthorizationHeaderName = "Authorization"
private const val bearerPrefix = "Bearer "

private const val googleAccountsHostname = "accounts.google.com"

private val googleJwksUri = URI("https://www.googleapis.com/oauth2/v3/certs").toURL()
private val googleIssuers = setOf("https://$googleAccountsHostname", googleAccountsHostname)

/**
 * Verifies a Google ID token passed as `Authorization: Bearer <token>`.
 *
 * Two caller classes present structurally different tokens, so audience and hosted-domain checks
 * branch on which one matched:
 * - **User** (Google Sign-In, browser SPA or the CLI): `aud` = one of [userTokenAudiences], `hd`
 *   must equal [allowedDomain].
 * - **Worker** (`flow work`, a service account, impersonated or key-based): `aud` =
 *   [workerTokenAudience] (this API's own public URL — see `WrkGrpcApiClient`, which mints the
 *   token with the API URL as target audience). Service-account tokens never carry an `hd` claim,
 *   so that check is skipped; the real access control for worker calls is `WorkerAuthorizer`'s
 *   email allowlist, checked downstream in `WorkerServiceImpl`.
 *
 * Common to both: valid signature against Google's JWKS, `iss` is a known Google issuer, token is
 * not expired. Returns HTTP 401 on any failure.
 */
class GoogleIdTokenAuthDecorator(
    // The OAuth client ids a *user* sign-in token may carry as `aud`. More than one because
    // different Google OAuth clients mint different audiences for the same person: the browser SPA
    // uses a Web client, while the `flow` CLI uses its own Desktop client (the loopback/PKCE flow
    // Google only permits for Desktop clients). A token is a valid user token if its `aud` matches
    // any of these; all still carry the `hd` hosted-domain claim, checked below.
    private val userTokenAudiences: Set<String>,
    private val allowedDomain: String,
    private val workerTokenAudience: String,
) : DecoratingHttpServiceFunction {
  companion object {
    private val unauthorized: HttpResponse
      get() = HttpResponse.of(HttpStatus.UNAUTHORIZED)
  }

  private val jwtProcessor = buildJwtProcessor()

  private fun buildJwtProcessor(): DefaultJWTProcessor<SecurityContext> {
    val jwkSource =
        JWKSourceBuilder.create<SecurityContext>(googleJwksUri).refreshAheadCache(true).build()

    val keySelector = JWSVerificationKeySelector(com.nimbusds.jose.JWSAlgorithm.RS256, jwkSource)

    // Audience isn't checked here (it differs by caller class) — checked manually in serve().
    val claimsVerifier =
        DefaultJWTClaimsVerifier<SecurityContext>(
            com.nimbusds.jwt.JWTClaimsSet.Builder().build(),
            setOf("sub", "email", "iat", "exp"),
        )

    return DefaultJWTProcessor<SecurityContext>().apply {
      jwsKeySelector = keySelector
      jwtClaimsSetVerifier = claimsVerifier
    }
  }

  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    val token = extractBearerToken(req) ?: return unauthorized

    val claims =
        try {
          jwtProcessor.process(token, null)
        } catch (_: Exception) {
          return unauthorized
        }

    val email = resolveAuthorizedEmail(claims) ?: return unauthorized
    ctx.setAttr(AuthenticatedUser.emailAttributeKey, email)

    return delegate.serve(ctx, req)
  }

  /**
   * Applies the issuer/audience/hosted-domain rules described in the class doc to already
   * signature-verified [claims], returning the caller's email if authorized, `null` otherwise.
   * Pulled out of [serve] so it's testable without a real signed token (signature/JWKS verification
   * requires live network access to Google).
   */
  internal fun resolveAuthorizedEmail(claims: JWTClaimsSet): String? {
    if (claims.issuer !in googleIssuers) return null

    // Compare audiences ignoring a trailing slash on either side: the token's `aud` and the
    // configured audiences both derive from the same API URL, but a stray slash (e.g. the API_URL
    // variable carrying one while a caller mints its token without) shouldn't cause a 401.
    val audiences = claims.audience.orEmpty().map { it.trimTrailingSlash() }

    when {
      workerTokenAudience.trimTrailingSlash() in audiences -> {
        // Worker (service-account) token: no hd claim to check; WorkerAuthorizer gates access.
      }

      userTokenAudiences.any { it.trimTrailingSlash() in audiences } -> {
        val hd = claims.getStringClaim("hd")
        if (hd != allowedDomain) return null
      }

      else -> return null
    }

    return claims.getStringClaim("email")
  }

  private fun String.trimTrailingSlash(): String = trimEnd('/')

  private fun extractBearerToken(req: HttpRequest): String? {
    val header = req.headers().get(httpAuthorizationHeaderName) ?: return null
    if (!header.startsWith(bearerPrefix)) return null
    return header.removePrefix(bearerPrefix).trim().takeIf { it.isNotEmpty() }
  }
}
