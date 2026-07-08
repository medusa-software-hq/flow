package software.medusa.flow.server

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
 * [GitHubIssueStore] backed by the GitHub REST API, authenticating as a GitHub App installation.
 *
 * The installation is discovered at runtime from the target repository, so only the App client ID
 * and private key need to be configured.
 */
class GitHubAppIssueStore(
    private val config: GitHubAppConfig,
    private val webClient: WebClient = WebClient.of(githubApiBaseUrl),
) : GitHubIssueStore {
  private val privateKey: RSAPrivateKey = parsePkcs8PrivateKey(config.pemContent)

  override suspend fun listRecentIssues(limit: Int): List<GitHubIssue> {
    val appJwt = createAppJwt()
    val installationId = fetchInstallationId(appJwt)
    val installationToken = fetchInstallationToken(installationId, appJwt)

    val path =
        "/repos/${config.repoOwner}/${config.repoName}/issues" +
            "?state=all&sort=created&direction=desc&per_page=$limit"

    val response = get(path, "Bearer $installationToken")

    check(response.status() == HttpStatus.OK) {
      "GitHub issues request failed: ${response.status()} ${response.contentUtf8()}"
    }

    // The issues endpoint also returns pull requests; filter them out via the `pull_request` field.
    return json
        .decodeFromString<List<IssueDto>>(response.contentUtf8())
        .filter { it.pullRequest == null }
        .map {
          GitHubIssue(
              number = it.number,
              title = it.title,
              state = it.state,
              url = it.htmlUrl,
              author = it.user?.login ?: "",
          )
        }
  }

  private suspend fun fetchInstallationId(appJwt: String): Long {
    val response =
        get("/repos/${config.repoOwner}/${config.repoName}/installation", "Bearer $appJwt")

    check(response.status() == HttpStatus.OK) {
      "GitHub installation lookup failed: ${response.status()} ${response.contentUtf8()}"
    }

    return json.decodeFromString<InstallationDto>(response.contentUtf8()).id
  }

  private suspend fun fetchInstallationToken(installationId: Long, appJwt: String): String {
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

    return json.decodeFromString<InstallationTokenResponse>(response.contentUtf8()).token
  }

  private suspend fun get(path: String, authorization: String) =
      webClient
          .prepare()
          .get(path)
          .header(HttpHeaderNames.AUTHORIZATION, authorization)
          .header(HttpHeaderNames.ACCEPT, githubAcceptHeader)
          .header(HttpHeaderNames.USER_AGENT, userAgent)
          .execute()
          .aggregate()
          .await()

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

  private companion object {
    const val githubApiBaseUrl = "https://api.github.com"
    const val githubAcceptHeader = "application/vnd.github+json"
    const val userAgent = "medusa-flow"

    val json = Json { ignoreUnknownKeys = true }

    fun parsePkcs8PrivateKey(pem: String): RSAPrivateKey {
      val base64 =
          pem.replace("-----BEGIN PRIVATE KEY-----", "")
              .replace("-----END PRIVATE KEY-----", "")
              .replace(Regex("\\s"), "")
      val keyBytes = Base64.getDecoder().decode(base64)
      return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
          as RSAPrivateKey
    }
  }
}

@Serializable private data class InstallationDto(val id: Long)

@Serializable private data class InstallationTokenResponse(val token: String)

@Serializable
private data class IssueDto(
    val number: Int,
    val title: String,
    val state: String,
    @SerialName("html_url") val htmlUrl: String,
    val user: UserDto? = null,
    @SerialName("pull_request") val pullRequest: PullRequestRef? = null,
)

@Serializable private data class UserDto(val login: String)

@Serializable private data class PullRequestRef(val url: String? = null)
