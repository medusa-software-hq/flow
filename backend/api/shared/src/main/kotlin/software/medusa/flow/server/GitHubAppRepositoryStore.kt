package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [GitHubRepositoryStore] backed by the GitHub REST API's `GET /installation/repositories`,
 * authenticating via [GitHubAppClient] — every repository the App installation (discovered from a
 * single configured repo) can see, not just that one repo.
 */
class GitHubAppRepositoryStore(
    private val client: GitHubAppClient,
) : GitHubRepositoryStore {
  private companion object {
    private const val perPage = 100
  }

  override suspend fun listRepositories(): List<GitHubRepository> {
    val repositories = mutableListOf<RepositoryDto>()
    var page = 1

    while (true) {
      val response = client.get("/installation/repositories?per_page=$perPage&page=$page")

      check(response.status() == HttpStatus.OK) {
        "GitHub installation repositories request failed: ${response.status()} ${response.contentUtf8()}"
      }

      val pageResult =
          gitHubJson.decodeFromString<InstallationRepositoriesDto>(response.contentUtf8())

      repositories += pageResult.repositories

      if (pageResult.repositories.size < perPage || repositories.size >= pageResult.totalCount) {
        break
      }

      page++
    }

    return repositories
        .map {
          GitHubRepository(
              fullName = it.fullName,
              defaultBranch = it.defaultBranch,
              url = it.htmlUrl,
          )
        }
        .sortedBy { it.fullName }
  }
}

@Serializable
private data class InstallationRepositoriesDto(
    val repositories: List<RepositoryDto>,
    @SerialName("total_count") val totalCount: Int,
)

@Serializable
private data class RepositoryDto(
    @SerialName("full_name") val fullName: String,
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("html_url") val htmlUrl: String,
)
