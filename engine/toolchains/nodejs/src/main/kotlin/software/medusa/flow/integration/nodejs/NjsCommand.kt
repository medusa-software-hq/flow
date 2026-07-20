package software.medusa.flow.integration.nodejs

/** Describes a resolved Node.js command that can be executed within a project. */
interface NjsCommand {
  data class ExecutionResult(
      val exitCode: Int,
      val standardOutput: String,
      val errorOutput: String,
  )

  /** Executes the command using the provided [arguments]. */
  suspend fun execute(
      arguments: List<String>,
  ): ExecutionResult
}
