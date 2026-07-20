package software.medusa.flow.integration.gradle

/**
 * Operations that can be executed once a Gradle project has been connected.
 *
 * The connection holds native resources (a Gradle Tooling API connection and, transitively, a
 * daemon) and must be [closed][close] when no longer needed.
 */
interface GrdProjectConnection : AutoCloseable {
  /** Runs [taskName] and returns a summary describing its outputs. */
  suspend fun runTask(taskName: GrdTaskName): GrdTaskResult
}
