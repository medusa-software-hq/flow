package software.medusa.flow.worker

import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace
import software.medusa.flow.harness.HrsTaskDescription

/** Branch/commit/push/PR publishing, behind a port so pipeline orchestration stays testable. */
fun interface WrkPublisher {
  /** Returns the URL of the opened PR. */
  suspend fun publish(
      repoFullName: String,
      taskDescription: HrsTaskDescription,
      workspace: HrsReadonlyTemporaryWorkspace,
  ): String
}
