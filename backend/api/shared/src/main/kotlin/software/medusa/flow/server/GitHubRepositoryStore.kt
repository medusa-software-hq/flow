package software.medusa.flow.server

/** A single GitHub repository, trimmed down to the fields the repo picker needs. */
data class GitHubRepository(
    val fullName: String,
    val defaultBranch: String,
    val url: String,
)

/** Read-only access to the repositories a GitHub identity can see. */
interface GitHubRepositoryStore {
  /** Returns every visible repository, sorted by full name. */
  suspend fun listRepositories(): List<GitHubRepository>
}
