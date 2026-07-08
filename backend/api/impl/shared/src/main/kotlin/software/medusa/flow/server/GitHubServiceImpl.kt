package software.medusa.flow.server

import software.medusa.flow.v1.GitHubServiceGrpcKt
import software.medusa.flow.v1.Issue
import software.medusa.flow.v1.ListIssuesRequest
import software.medusa.flow.v1.ListIssuesResponse
import software.medusa.flow.v1.listIssuesResponse

private const val issueLimit = 10

class GitHubServiceImpl(
    private val gitHubIssueStore: GitHubIssueStore,
) : GitHubServiceGrpcKt.GitHubServiceCoroutineImplBase() {
  override suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse {
    val issues = gitHubIssueStore.listRecentIssues(issueLimit)
    return listIssuesResponse {
      this.issues += issues.map { issue ->
        Issue.newBuilder()
            .setNumber(issue.number)
            .setTitle(issue.title)
            .setState(issue.state)
            .setUrl(issue.url)
            .setAuthor(issue.author)
            .build()
      }
    }
  }
}
