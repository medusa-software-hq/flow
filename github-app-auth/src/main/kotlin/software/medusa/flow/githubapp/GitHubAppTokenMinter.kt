package software.medusa.flow.githubapp

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlinx.coroutines.future.await
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Configuration for a GitHub App installation with access to a single repository.
 *
 * [pemContent] must be an unencrypted PKCS#8 PEM (a `BEGIN PRIVATE KEY` block). GitHub issues App
 * keys in PKCS#1; convert once with: `openssl pkcs8 -topk8 -nocrypt -in app.pem -out app.pk8.pem`.
 */
data class GitHubAppConfig(
    val clientId: String,
    val pemContent: String,
    val repoOwner: String,
    val repoName: String,
)

/** A minted installation access token and the instant GitHub says it expires. */
data class MintedGitHubAppToken(val token: String, val expiresAt: Instant)

/**
 * Mints GitHub App **installation access tokens** for one repository: sign an App JWT with the
 * private key, discover the installation from
 * [GitHubAppConfig.repoOwner]/[GitHubAppConfig.repoName], then exchange the JWT for a short-lived
 * (~1h) installation token. Stateless — each [mint] does the full dance and returns the token with
 * its expiry, so callers can cache/refresh as they see fit (the backend mints fresh per request;
 * the worker refreshes per session).
 */
class GitHubAppTokenMinter(
    private val config: GitHubAppConfig,
    private val webClient: WebClient = WebClient.of(GITHUB_API_BASE_URL),
) {
  companion object {
    const val GITHUB_API_BASE_URL = "https://api.github.com"
    private const val ACCEPT_HEADER = "application/vnd.github+json"
    private const val USER_AGENT = "medusa-flow"
    private val json = Json { ignoreUnknownKeys = true }

    private fun parsePkcs8PrivateKey(pem: String): RSAPrivateKey {
      val base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace(Regex("\\s"), "")
      val keyBytes = Base64.getDecoder().decode(base64)
      return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
          as RSAPrivateKey
    }
  }

  private val privateKey: RSAPrivateKey = parsePkcs8PrivateKey(config.pemContent)

  suspend fun mint(): MintedGitHubAppToken {
    val appJwt = createAppJwt()
    val installationId = fetchInstallationId(appJwt)

    val response =
        webClient
            .prepare()
            .post("/app/installations/$installationId/access_tokens")
            .header(HttpHeaderNames.AUTHORIZATION, "Bearer $appJwt")
            .header(HttpHeaderNames.ACCEPT, ACCEPT_HEADER)
            .header(HttpHeaderNames.USER_AGENT, USER_AGENT)
            .execute()
            .aggregate()
            .await()

    check(response.status() == HttpStatus.CREATED) {
      "GitHub installation token request failed: ${response.status()} ${response.contentUtf8()}"
    }

    val dto = json.decodeFromString<InstallationTokenResponse>(response.contentUtf8())
    return MintedGitHubAppToken(token = dto.token, expiresAt = Instant.parse(dto.expiresAt))
  }

  private suspend fun fetchInstallationId(appJwt: String): Long {
    val response =
        webClient
            .prepare()
            .get("/repos/${config.repoOwner}/${config.repoName}/installation")
            .header(HttpHeaderNames.AUTHORIZATION, "Bearer $appJwt")
            .header(HttpHeaderNames.ACCEPT, ACCEPT_HEADER)
            .header(HttpHeaderNames.USER_AGENT, USER_AGENT)
            .execute()
            .aggregate()
            .await()

    check(response.status() == HttpStatus.OK) {
      "GitHub installation lookup failed: ${response.status()} ${response.contentUtf8()}"
    }

    return json.decodeFromString<InstallationDto>(response.contentUtf8()).id
  }

  private fun createAppJwt(): String {
    val now = Instant.now()
    val claims =
        JWTClaimsSet.Builder()
            // GitHub accepts the App's client ID as the issuer.
            .issuer(config.clientId)
            // Allow 60s of clock drift, and stay well under GitHub's 10-minute maximum.
            .issueTime(Date.from(now.minusSeconds(60)))
            .expirationTime(Date.from(now.plusSeconds(9 * 60)))
            .build()

    val signedJwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).build(), claims)
    signedJwt.sign(RSASSASigner(privateKey))
    return signedJwt.serialize()
  }
}

@Serializable private data class InstallationDto(val id: Long)

@Serializable
private data class InstallationTokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
)
