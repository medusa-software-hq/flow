package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Exercises [GitHubAppRepositoryStore] against a real (fake) HTTP server standing in for the GitHub
 * API, covering the hand-rolled pagination loop and the DTO field mapping (`full_name` /
 * `default_branch` / `html_url` -> the domain type) end to end — the whole App-auth call sequence
 * (JWT -> installation lookup -> installation token -> paginated repo list) has no other test
 * coverage.
 */
class GitHubAppRepositoryStore_serverTests {
  private companion object {
    private const val perPage = 100

    private val testPemContent: String = run {
      val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
      val base64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
      "-----BEGIN PRIVATE KEY-----\n$base64\n-----END PRIVATE KEY-----"
    }

    private fun repositoryJson(
        fullName: String,
    ): String =
        """{"full_name":"$fullName","default_branch":"main","html_url":"https://github.com/$fullName"}"""

    private fun installationRepositoriesPageJson(
        totalCount: Int,
        repositories: List<String>,
    ): String = """{"total_count":$totalCount,"repositories":[${repositories.joinToString(",")}]}"""
  }

  private lateinit var server: Server

  @AfterTest
  fun tearDown() {
    if (::server.isInitialized) server.stop().join()
  }

  private fun buildClient(
      totalCount: Int,
      pageSizes: List<Int>,
  ): GitHubAppRepositoryStore {
    server =
        Server.builder()
            .http(0)
            .service("/repos/acme/app/installation") { _, _ ->
              HttpResponse.of(MediaType.JSON, """{"id":42}""")
            }
            .service("/app/installations/42/access_tokens") { _, _ ->
              HttpResponse.of(HttpStatus.CREATED, MediaType.JSON, """{"token":"fake-token"}""")
            }
            .service("/installation/repositories") { ctx, _ ->
              val page = ctx.queryParam("page")?.toInt() ?: 1
              val startIndex = (page - 1) * perPage
              val count = pageSizes.getOrElse(page - 1) { 0 }

              val repositories =
                  (startIndex until startIndex + count).map { repositoryJson("acme/repo-$it") }

              HttpResponse.of(
                  MediaType.JSON,
                  installationRepositoriesPageJson(totalCount, repositories),
              )
            }
            .build()
            .also { it.start().join() }

    val webClient = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")

    val client =
        GitHubAppClient(
            config =
                GitHubAppConfig(
                    clientId = "test-client-id",
                    pemContent = testPemContent,
                    repoOwner = "acme",
                    repoName = "app",
                ),
            webClient = webClient,
        )

    return GitHubAppRepositoryStore(client)
  }

  @Test
  fun `a single page is returned sorted by full name`() = runBlocking {
    val store = buildClient(totalCount = 2, pageSizes = listOf(2))

    val repositories = store.listRepositories()

    assertEquals(listOf("acme/repo-0", "acme/repo-1"), repositories.map { it.fullName })
    assertEquals("main", repositories.first().defaultBranch)
    assertEquals("https://github.com/acme/repo-0", repositories.first().url)
  }

  @Test
  fun `multiple full pages are all fetched and combined`() = runBlocking {
    // total_count spans three pages: two full pages of 100 plus a partial third page.
    val store = buildClient(totalCount = 250, pageSizes = listOf(100, 100, 50))

    val repositories = store.listRepositories()

    assertEquals(250, repositories.size)
    assertEquals(
        (0 until 250).map { "acme/repo-$it" }.sorted(),
        repositories.map { it.fullName },
    )
  }

  @Test
  fun `a short final page stops pagination even if total_count undercounts`() = runBlocking {
    // A page shorter than per_page is itself a stop signal, independent of total_count.
    val store = buildClient(totalCount = 1000, pageSizes = listOf(100, 30))

    val repositories = store.listRepositories()

    assertEquals(130, repositories.size)
  }
}
