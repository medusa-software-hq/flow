package software.medusa.flow.server

/**
 * In-memory [GitHubIssueStore] returning canned sample issues.
 *
 * Used for local development and as a fallback when GitHub App credentials are not configured, so
 * the app stays functional without reaching out to GitHub.
 */
class FakeGitHubIssueStore : GitHubIssueStore {
  private val issues =
      listOf(
          GitHubIssue(
              number = 10,
              title = "Add dark mode toggle",
              state = "open",
              url = "https://github.com/medusa-software-hq/flow/issues/10",
              author = "octocat",
          ),
          GitHubIssue(
              number = 9,
              title = "Flaky integration test on CI",
              state = "open",
              url = "https://github.com/medusa-software-hq/flow/issues/9",
              author = "hubot",
          ),
          GitHubIssue(
              number = 8,
              title = "Document the deployment process",
              state = "closed",
              url = "https://github.com/medusa-software-hq/flow/issues/8",
              author = "octocat",
          ),
      )

  override suspend fun listRecentIssues(limit: Int): List<GitHubIssue> = issues.take(limit)
}
