package software.medusa.flow.server

/** The state of a pull request relevant to advancing a pipeline out of `PR_OPEN`. */
sealed interface PullRequestState {
  /** Still open — the pipeline stays `PR_OPEN`. */
  data object Open : PullRequestState

  /** Merged; [mergeCommitSha] is the commit whose merge-checks the gate evaluates. */
  data class Merged(
      val mergeCommitSha: String,
  ) : PullRequestState

  /** Closed without merging — the pipeline fails. */
  data object ClosedUnmerged : PullRequestState
}

/**
 * The merge gate verdict for a merge commit's Actions results (see the spike,
 * `plan/m2/design/04-github-api-notes.md`). [NoRuns] is structurally distinct from [Pending] — the
 * commit's status-check rollup is absent, not in-progress — which lets "no merge workflows
 * configured" become a vacuous success without a timer racing Actions startup.
 */
sealed interface MergeCheckStatus {
  /** Every run completed successfully / neutral / skipped. */
  data object Green : MergeCheckStatus

  /** At least one run failed / was cancelled / timed out; [failingRunNames] for the annotation. */
  data class Red(
      val failingRunNames: List<String>,
  ) : MergeCheckStatus

  /** Runs exist but are still queued or running. */
  data object Pending : MergeCheckStatus

  /** No runs at all for the commit — vacuous success once the grace period elapses. */
  data object NoRuns : MergeCheckStatus
}

/**
 * The read side of GitHub used by the reconcile observe phase: PR state and merge-commit check
 * results. A port (following the `GitHub*` pattern) with a real impl ([GitHubAppPrClient]) and a
 * scriptable [FakeGitHubPrClient] for tests.
 */
interface GitHubPrClient {
  suspend fun getPullRequestState(
      repoFullName: String,
      prNumber: Int,
  ): PullRequestState

  suspend fun getMergeCheckStatus(
      repoFullName: String,
      commitSha: String,
  ): MergeCheckStatus
}
