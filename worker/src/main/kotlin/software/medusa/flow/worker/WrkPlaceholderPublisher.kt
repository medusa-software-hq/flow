package software.medusa.flow.worker

import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace
import software.medusa.flow.harness.HrsTaskDescription

/**
 * Placeholder [WrkPublisher]: a successful engine run completes with a canned URL instead of an
 * actual PR. Removed once branch/commit/push/PR publishing (story 14) lands.
 */
object WrkPlaceholderPublisher : WrkPublisher {
  override suspend fun publish(
      repoFullName: String,
      taskDescription: HrsTaskDescription,
      workspace: HrsReadonlyTemporaryWorkspace,
  ): String = "https://example.com/flow-worker-placeholder-pr/$repoFullName"
}
