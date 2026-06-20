package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.gradle.GrdTaskName
import software.medusa.flow.integration.gradle.GrdTaskResult

/**
 * A gradle connection whose tasks are real [FakeGrdTask]s operating on the module's in-memory
 * directory. Symmetric to [FakeNodeJsPackageConnection], except a gradle build has no install step,
 * so its tasks are available directly; an unknown task fails, as a real build would.
 */
class FakeGradleProjectConnection(
    moduleDirectory: UfsMutableDirectory,
) : GrdProjectConnection {
  private val taskByName: Map<String, FakeGrdTask> =
      mapOf(
          "generate" to FakeGrdTask.GenerateFile(moduleDirectory),
          "lint" to FakeGrdTask.CheckLowercaseSources(moduleDirectory),
          "assemble" to FakeGrdTask.AlwaysSucceed,
          "test" to FakeGrdTask.AlwaysSucceed,
      )

  override suspend fun runTask(
      taskName: GrdTaskName,
  ): GrdTaskResult =
      taskByName[taskName.name]?.run()
          ?: GrdTaskResult(
              status = GrdTaskResult.Status.Failure,
              standardOutput = "",
              errorOutput = "Task not found: ${taskName.name}",
          )

  override fun close() = Unit
}
