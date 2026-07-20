package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.flow.integration.nodejs.NjsCommand

/** A small hierarchy of stand-in Node.js commands that do real, trivial work on the module tree. */
sealed class FakeNjsCommand : NjsCommand {
  /** Generates code by writing a file into the module — a stand-in for e.g. `buf generate`. */
  class GenerateFile(
      private val moduleDirectory: UfsMutableDirectory,
  ) : FakeNjsCommand() {
    override suspend fun execute(arguments: List<String>): NjsCommand.ExecutionResult {
      generateFileInto(moduleDirectory)

      return success(standardOutput = "generated")
    }
  }

  /** Validates that every file under `src/` has an all-lowercase name — a stand-in for a linter. */
  class CheckLowercaseSources(
      private val moduleDirectory: UfsMutableDirectory,
  ) : FakeNjsCommand() {
    override suspend fun execute(arguments: List<String>): NjsCommand.ExecutionResult {
      val offenders = lowercaseOffenders(moduleDirectory)

      return if (offenders.isEmpty()) {
        success(standardOutput = "ok")
      } else {
        failure(errorOutput = "non-lowercase sources: ${offenders.joinToString()}")
      }
    }
  }

  /** Always succeeds — a stand-in for a test runner whose behavior we do not model. */
  data object AlwaysSucceed : FakeNjsCommand() {
    override suspend fun execute(arguments: List<String>): NjsCommand.ExecutionResult =
        success(standardOutput = "ok")
  }

  protected fun success(
      standardOutput: String,
  ): NjsCommand.ExecutionResult =
      NjsCommand.ExecutionResult(exitCode = 0, standardOutput = standardOutput, errorOutput = "")

  protected fun failure(
      errorOutput: String,
  ): NjsCommand.ExecutionResult =
      NjsCommand.ExecutionResult(exitCode = 1, standardOutput = "", errorOutput = errorOutput)
}
