package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
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
import kotlinx.serialization.Serializable

/**
 * Configuration for a GitHub App installation with read access to a single repository.
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

/**
 * A minimal authenticated GitHub REST client for a GitHub App installation, shared by every
 * `GitHubApp*Store`. The installation is discovered at runtime from [GitHubAppConfig.repoOwner] /
 * [GitHubAppConfig.repoName], so only the App client ID and private key need to be configured.
 *
 * Each [get] call mints a fresh installation access token (GitHub App JWTs and installation tokens
 * are both short-lived, and this client is called rarely enough — a handful of API requests per
 * user action — that a cache isn't worth the added state).
 */
class GitHubAppClient(
    private val config: GitHubAppConfig,
    private val webClient: WebClient = WebClient.of(githubApiBaseUrl),
) {
  companion object {
    const val githubApiBaseUrl = "https://api.github.com"
    const val githubAcceptHeader = "application/vnd.github+json"
    const val userAgent = "medusa-flow"

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

  /** The repository the installation was discovered from (`repos/{repoOwner}/{repoName}`). */
  val repoOwner: String
    get() = config.repoOwner

  val repoName: String
    get() = config.repoName

  /** Performs an authenticated `GET` against [path] as the installation. */
  suspend fun get(
      path: String,
  ): AggregatedHttpResponse = request(HttpMethod.GET, path, body = null)

  /** Performs an authenticated `POST` against [path] with an optional JSON [body]. */
  suspend fun post(
      path: String,
      body: String? = null,
  ): AggregatedHttpResponse = request(HttpMethod.POST, path, body)

  /** Performs an authenticated `PATCH` against [path] with a JSON [body]. */
  suspend fun patch(
      path: String,
      body: String,
  ): AggregatedHttpResponse = request(HttpMethod.PATCH, path, body)

  /** Performs an authenticated `DELETE` against [path]. */
  suspend fun delete(
      path: String,
  ): AggregatedHttpResponse = request(HttpMethod.DELETE, path, body = null)

  private suspend fun request(
      method: HttpMethod,
      path: String,
      body: String?,
  ): AggregatedHttpResponse {
    val installationToken = fetchInstallationToken()

    val requestHeaders =
        RequestHeaders.builder(method, path)
            .add(HttpHeaderNames.AUTHORIZATION, "Bearer $installationToken")
            .add(HttpHeaderNames.ACCEPT, githubAcceptHeader)
            .add(HttpHeaderNames.USER_AGENT, userAgent)
            .apply { if (body != null) contentType(MediaType.JSON) }
            .build()

    val httpRequest =
        if (body != null) HttpRequest.of(requestHeaders, HttpData.ofUtf8(body))
        else HttpRequest.of(requestHeaders)

    return webClient.execute(httpRequest).aggregate().await()
  }

  private suspend fun fetchInstallationToken(): String {
    val appJwt = createAppJwt()
    val installationId = fetchInstallationId(appJwt)

    val response =
        webClient
            .prepare()
            .post("/app/installations/$installationId/access_tokens")
            .header(HttpHeaderNames.AUTHORIZATION, "Bearer $appJwt")
            .header(HttpHeaderNames.ACCEPT, githubAcceptHeader)
            .header(HttpHeaderNames.USER_AGENT, userAgent)
            .execute()
            .aggregate()
            .await()

    check(response.status() == HttpStatus.CREATED) {
      "GitHub installation token request failed: ${response.status()} ${response.contentUtf8()}"
    }

    return gitHubJson.decodeFromString<InstallationTokenResponse>(response.contentUtf8()).token
  }

  private suspend fun fetchInstallationId(
      appJwt: String,
  ): Long {
    val response =
        webClient
            .prepare()
            .get("/repos/${config.repoOwner}/${config.repoName}/installation")
            .header(HttpHeaderNames.AUTHORIZATION, "Bearer $appJwt")
            .header(HttpHeaderNames.ACCEPT, githubAcceptHeader)
            .header(HttpHeaderNames.USER_AGENT, userAgent)
            .execute()
            .aggregate()
            .await()

    check(response.status() == HttpStatus.OK) {
      "GitHub installation lookup failed: ${response.status()} ${response.contentUtf8()}"
    }

    return gitHubJson.decodeFromString<InstallationDto>(response.contentUtf8()).id
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

/** Shared, lenient JSON codec for the small GitHub REST DTOs used across `GitHubApp*` stores. */
internal val gitHubJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

@Serializable private data class InstallationDto(val id: Long)

@Serializable private data class InstallationTokenResponse(val token: String)
