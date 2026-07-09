package software.medusa.flow.server

import io.grpc.Status
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import software.medusa.flow.v1.GitHubServiceGrpcKt
import software.medusa.flow.v1.Issue
import software.medusa.flow.v1.ListIssuesRequest
import software.medusa.flow.v1.ListIssuesResponse
import software.medusa.flow.v1.ListRepositoriesRequest
import software.medusa.flow.v1.ListRepositoriesResponse
import software.medusa.flow.v1.listIssuesResponse
import software.medusa.flow.v1.listRepositoriesResponse
import software.medusa.flow.v1.repository

private const val issueLimit = 10

class GitHubServiceImpl(
    private val gitHubIssueStore: GitHubIssueStore,
    private val gitHubRepositoryStore: GitHubRepositoryStore,
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

  override suspend fun listRepositories(
      request: ListRepositoriesRequest,
  ): ListRepositoriesResponse {
    val repositories =
        try {
          gitHubRepositoryStore.listRepositories()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          logger.error("Failed to list GitHub repositories", e)
          throw Status.INTERNAL.withDescription(e.message ?: e.javaClass.name)
              .withCause(e)
              .asRuntimeException()
        }

    return listRepositoriesResponse {
      this.repositories += repositories.map { repo ->
        repository {
          fullName = repo.fullName
          defaultBranch = repo.defaultBranch
          url = repo.url
        }
      }
    }
  }
}
