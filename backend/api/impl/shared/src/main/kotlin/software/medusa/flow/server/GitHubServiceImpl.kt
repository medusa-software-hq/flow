package software.medusa.flow.server

import io.grpc.Status
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import software.medusa.flow.v1.GitHubServiceGrpcKt
import software.medusa.flow.v1.Issue
import software.medusa.flow.v1.ListIssuesRequest
import software.medusa.flow.v1.ListIssuesResponse
import software.medusa.flow.v1.listIssuesResponse

private const val issueLimit = 10

class GitHubServiceImpl(
    private val gitHubIssueStore: GitHubIssueStore,
) : GitHubServiceGrpcKt.GitHubServiceCoroutineImplBase() {
  private val logger = LoggerFactory.getLogger(GitHubServiceImpl::class.java)

  override suspend fun listIssues(request: ListIssuesRequest): ListIssuesResponse {
    val issues =
        try {
          gitHubIssueStore.listRecentIssues(issueLimit)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // Otherwise the failure is swallowed into an opaque gRPC UNKNOWN with no log line.
          logger.error("Failed to list GitHub issues", e)
          throw Status.INTERNAL.withDescription(e.message ?: e.javaClass.name)
              .withCause(e)
              .asRuntimeException()
        }

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
