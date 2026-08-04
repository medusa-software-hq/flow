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

/** The merge method GitHub arms auto-merge with. Quick Settings' auto-merge is fixed to [Merge]. */
enum class MergeMethod {
  Merge,
  Squash,
  Rebase,
}

/**
 * Outcome of [GitHubPrClient.armAutoMerge] — never throws; a failure is a value, not an exception.
 */
sealed interface AutoMergeResult {
  /** GitHub accepted the request; the PR merges itself once required checks pass. */
  data object Armed : AutoMergeResult

  /**
   * GitHub rejected the request (auto-merge disabled for the repo, no branch protection / required
   * checks, or the PR already merged/closed). [reason] is logged; the caller must otherwise treat
   * this like [Armed] never happened — the pipeline still merge-watches and a human can merge.
   */
  data class Failed(
      val reason: String,
  ) : AutoMergeResult
}

/**
 * The read side of GitHub used by the reconcile observe phase: PR state and merge-commit check
 * results, plus the one write — arming auto-merge (Quick Settings' `auto_merge`). A port (following
 * the `GitHub*` pattern) with a real impl ([GitHubAppPrClient]) and a scriptable
 * [FakeGitHubPrClient] for tests.
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

  /**
   * Arms GitHub auto-merge on `repoFullName#prNumber` (GraphQL `enablePullRequestAutoMerge`). Never
   * throws — a repo/PR that can't support auto-merge (no branch protection, required checks
   * missing, auto-merge disabled repo-wide, ...) surfaces as [AutoMergeResult.Failed], not an
   * exception, so a reconcile cycle is never lost over it.
   */
  suspend fun armAutoMerge(
      repoFullName: String,
      prNumber: Int,
      mergeMethod: MergeMethod = MergeMethod.Merge,
  ): AutoMergeResult
}
