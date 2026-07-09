package software.medusa.flow.worker

import java.nio.file.Path
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace

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
   */
  suspend fun publish(
      repoFullName: String,
      sessionId: String,
      taskHeading: String,
      taskMarkdown: String,
      cloneDirectory: Path,
      workspace: HrsReadonlyTemporaryWorkspace,
  ): WrkPublishResult
}
