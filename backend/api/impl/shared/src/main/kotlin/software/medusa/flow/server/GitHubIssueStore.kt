package software.medusa.flow.server

/** A single GitHub issue, trimmed down to the fields the app cares about. */
data class GitHubIssue(
    val number: Int,
    val title: String,
    val state: String,
    val url: String,
    val author: String,
)

/** Read-only access to a repository's issues. */
interface GitHubIssueStore {
  /** Returns the most recently created issues, newest first, capped at [limit]. */
  suspend fun listRecentIssues(limit: Int): List<GitHubIssue>
}
