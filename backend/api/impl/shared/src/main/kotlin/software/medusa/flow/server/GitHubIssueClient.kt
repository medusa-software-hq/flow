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
   * Ensures every label in [flowLabels] exists in [repoFullName], creating (or, in the future,
   * updating color/description of) any that are missing. Idempotent: creating a label that already
   * exists is treated as success. This is Flow's *only* onboarding step — pointing Flow at a repo
   * that has none of the `flow:` labels is enough to make it pickup-able, with zero per-repo
   * Terraform.
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

  /** One entry of the [flowLabels] manifest: a label name with its canonical GitHub metadata. */
  data class FlowLabel(
      val name: String,
      val color: String, // hex, no leading `#`
      val description: String,
  )

  companion object {
    /**
     * The canonical `flow:` label manifest: every label Flow provisions on a repo it operates on,
     * name -> color/description. This is the whole of Flow's input+state label contract — the input
     * side ([GitHubCandidateClient.readyLabel]) and the state side Flow stamps at runtime
     * ([IssuePipelineStore.labelInProgress] et al.).
     *
     * Adding an entry provisions it everywhere on next [ensureLabelsExist]. Removing one only stops
     * provisioning it going forward — it is never deleted from a repo (see the module doc for the
     * ticket rationale: deleting a label strips it off every issue that carries it, irreversibly).
     * `priority:*` is deliberately absent: it stays Terraform-owned on the Flow repo itself.
     */
    val flowLabels: List<FlowLabel> =
        listOf(
            FlowLabel(
                GitHubCandidateClient.readyLabel,
                "5319e7", // purple
                "Opt an issue into Flow's pickup queue.",
            ),
            FlowLabel(
                IssuePipelineStore.labelInProgress,
                "1d76db", // blue
                "Flow has picked this issue and is working it.",
            ),
            FlowLabel(
                IssuePipelineStore.labelPrOpen,
                "0e8a16", // green
                "Flow opened a PR for this issue; awaiting merge.",
            ),
            FlowLabel(
                IssuePipelineStore.labelFailed,
                "d73a4a", // red
                "Flow's last attempt on this issue failed and needs attention.",
            ),
        )
  }
}
