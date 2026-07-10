package software.medusa.flow.server

/**
 * The write side of GitHub used by the outbox dispatcher: the label/comment/close side effects of a
 * pipeline transition. Every operation is idempotent except [postComment] (see [OutboxDispatcher]).
 *
 * A port (following the `GitHub*Store` pattern) with a real REST impl ([GitHubAppIssueClient]) and
 * a recording [FakeGitHubIssueClient] for tests.
 */
interface GitHubIssueClient {
  /**
   * Ensures the `flow:*` label definitions exist in [repoFullName]. Idempotent: creating a label
   * that already exists is treated as success (first-use setup).
   */
  suspend fun ensureLabelsExist(
      repoFullName: String,
  )

  /** Adds [label] to the issue. Idempotent (adding an existing label is a no-op on GitHub). */
  suspend fun addLabel(
      repoFullName: String,
      issueNumber: Int,
      label: String,
  )

  /**
   * Removes [label] from the issue. Idempotent (removing an absent label is treated as success).
   */
  suspend fun removeLabel(
      repoFullName: String,
      issueNumber: Int,
      label: String,
  )

  /** Posts a comment. NOT idempotent — re-posting creates a duplicate (see [OutboxDispatcher]). */
  suspend fun postComment(
      repoFullName: String,
      issueNumber: Int,
      body: String,
  )

  /** Closes the issue. Idempotent (closing a closed issue is a no-op). */
  suspend fun closeIssue(
      repoFullName: String,
      issueNumber: Int,
  )

  companion object {
    /** The `flow:*` labels the reconciler projects, with their GitHub colors (hex, no `#`). */
    val flowLabelColors: Map<String, String> =
        mapOf(
            IssuePipelineStore.labelInProgress to "1d76db", // blue
            IssuePipelineStore.labelPrOpen to "0e8a16", // green
            IssuePipelineStore.labelFailed to "d73a4a", // red
        )
  }
}
