package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import kotlinx.coroutines.future.await
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubapp.GitHubAppTokenMinter

/**
 * A minimal authenticated GitHub REST client for a GitHub App installation, shared by every
 * `GitHubApp*Store`. The installation is discovered at runtime from [GitHubAppConfig.repoOwner] /
 * [GitHubAppConfig.repoName], so only the App client ID and private key need to be configured.
 *
 * Each [get] call mints a fresh installation access token via [GitHubAppTokenMinter] (GitHub App
 * JWTs and installation tokens are both short-lived, and this client is called rarely enough — a
 * handful of API requests per user action — that a cache isn't worth the added state).
 */
class GitHubAppClient(
    config: GitHubAppConfig,
    private val webClient: WebClient = WebClient.of(githubApiBaseUrl),
) {
  companion object {
    const val githubApiBaseUrl = GitHubAppTokenMinter.GITHUB_API_BASE_URL
    const val githubAcceptHeader = "application/vnd.github+json"
    const val userAgent = "medusa-flow"
  }

  // Shares the client's WebClient, so a base-URL override (tests, the fake GitHub) covers both the
  // token mint and the REST calls below.
  private val minter = GitHubAppTokenMinter(config, webClient)

  /** The repository the installation was discovered from (`repos/{repoOwner}/{repoName}`). */
  val repoOwner: String = config.repoOwner

  val repoName: String = config.repoName

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
    val installationToken = minter.mint().token

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
}

/** Shared, lenient JSON codec for the small GitHub REST DTOs used across `GitHubApp*` stores. */
internal val gitHubJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
