package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.flow.integration.gradle.GrdTaskResult

/** A small hierarchy of stand-in Gradle tasks that do real, trivial work on the module tree. */
sealed class FakeGrdTask {
  abstract suspend fun run(): GrdTaskResult

  /** Generates code by writing a file into the module — a stand-in for e.g. a codegen task. */
  class GenerateFile(
      private val moduleDirectory: UfsMutableDirectory,
  ) : FakeGrdTask() {
    override suspend fun run(): GrdTaskResult {
      generateFileInto(moduleDirectory)

      return success(standardOutput = "generated")
    }
  }

  /** Validates that every file under `src/` has an all-lowercase name — a stand-in for a linter. */
  class CheckLowercaseSources(
      private val moduleDirectory: UfsMutableDirectory,
  ) : FakeGrdTask() {
    override suspend fun run(): GrdTaskResult {
      val offenders = lowercaseOffenders(moduleDirectory)

      return if (offenders.isEmpty()) {
        success(standardOutput = "ok")
      } else {
        failure(errorOutput = "non-lowercase sources: ${offenders.joinToString()}")
      }
    }
  }

  /** Always succeeds — a stand-in for a task whose behavior we do not model (assemble, test, …). */
  data object AlwaysSucceed : FakeGrdTask() {
    override suspend fun run(): GrdTaskResult = success(standardOutput = "ok")
  }

  protected fun success(
      standardOutput: String,
  ): GrdTaskResult =
      GrdTaskResult(
          status = GrdTaskResult.Status.Success,
          standardOutput = standardOutput,
          errorOutput = "",
      )

  protected fun failure(
      errorOutput: String,
  ): GrdTaskResult =
      GrdTaskResult(
          status = GrdTaskResult.Status.Failure,
          standardOutput = "",
          errorOutput = errorOutput,
      )
}
