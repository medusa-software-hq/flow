package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** [GitHubIssueStore] backed by the GitHub REST API, authenticating via [GitHubAppClient]. */
class GitHubAppIssueStore(
    private val client: GitHubAppClient,
) : GitHubIssueStore {
  override suspend fun listRecentIssues(limit: Int): List<GitHubIssue> {
    val response =
        client.get(
            "/repos/${client.repoOwner}/${client.repoName}/issues" +
                "?state=all&sort=created&direction=desc&per_page=$limit",
        )

    check(response.status() == HttpStatus.OK) {
      "GitHub issues request failed: ${response.status()} ${response.contentUtf8()}"
    }

    // The issues endpoint also returns pull requests; filter them out via the `pull_request` field.
    return gitHubJson
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
}

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
