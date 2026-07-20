package software.medusa.flow.server

/**
 * In-memory [GitHubRepositoryStore] returning canned sample repositories.
 *
 * Used for local development, so the new-session repo picker stays developable offline.
 */
class FakeGitHubRepositoryStore : GitHubRepositoryStore {
  private val repositories =
      listOf(
          GitHubRepository(
              fullName = "medusa-software-hq/flow",
              defaultBranch = "trunk/baseline3",
              url = "https://github.com/medusa-software-hq/flow",
          ),
          GitHubRepository(
              fullName = "medusa-software-hq/commons",
              defaultBranch = "main",
              url = "https://github.com/medusa-software-hq/commons",
          ),
      )

  override suspend fun listRepositories(): List<GitHubRepository> = repositories.sortedBy {
    it.fullName
  }
}
