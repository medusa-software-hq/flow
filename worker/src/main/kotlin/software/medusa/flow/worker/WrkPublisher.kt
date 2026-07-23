package software.medusa.flow.worker

import java.nio.file.Path
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace
import software.medusa.flow.v1.Engine

/** Outcome of a publish attempt. */
sealed interface WrkPublishResult {
  data class Published(
      val prUrl: String,
  ) : WrkPublishResult

  /** The materialized workspace has no diff against the clone; nothing was pushed. */
  data object NoChanges : WrkPublishResult
}

/** Branch/commit/push/PR publishing, behind a port so pipeline orchestration stays testable. */
fun interface WrkPublisher {
  /**
   * [cloneDirectory] is the on-disk clone [WrkGitCloner] produced -- publishing branches, commits,
   * and pushes from there. [taskHeading] is the task's first heading/line, used as the commit
   * subject and PR title.
   *
   * [issueNumber] links the PR to an issue pipeline (story 09): non-null switches the branch to
   * `flow/issue-<n>-<engine>` and adds a `Refs #<n>` (never a closing keyword — the reconciler
   * closes the issue, gated on the merge checks). Null is a manual session — identical to M1
   * behavior.
   *
   * [engine] scopes an issue-linked branch and tags its PR title/body, so the two dual-engine
   * sessions of one issue (Claude primary + built-in shadow) don't collide on the same branch and
   * are distinguishable in the PR list. Ignored on the manual path (single-engine, already unique).
   */
  suspend fun publish(
      repoFullName: String,
      sessionId: String,
      taskHeading: String,
      taskMarkdown: String,
      cloneDirectory: Path,
      workspace: HrsReadonlyTemporaryWorkspace,
      issueNumber: Int?,
      engine: Engine,
  ): WrkPublishResult
}
