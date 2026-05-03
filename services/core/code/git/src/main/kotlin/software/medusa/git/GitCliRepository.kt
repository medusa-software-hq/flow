package software.medusa.git

import java.nio.file.Path
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

internal class GitCliRepository(
    override val path: Path,
    private val gitExecutableHandle: ExecutableHandle,
    private val processSpawner: ProcessSpawner,
) : GitRepository {
  override fun commit(message: String): GitCommitResult {
    require(message.isNotBlank()) { "Commit message must not be blank" }

    runGitCommand(
        subcommandName = "add", // https://git-scm.com/docs/git-add
        subcommandArgs =
            listOf(
                "--all", // adds, modifies, and removes index entries to match the working tree.
            ),
    )

    runGitCommand(
        subcommandName = "commit", // https://git-scm.com/docs/git-commit
        subcommandArgs =
            listOf(
                "--message", // use <msg> as the commit message
                message, // <msg>
            ),
        config =
            mapOf(
                "user.name" to "Flow",
                "user.email" to "flow@medusa.software",
            ),
    )

    val commitHash =
        runGitCommand(
                subcommandName = "rev-parse",
                subcommandArgs = listOf("HEAD"),
            )
            .trim()

    return GitCommitResult(commitHash = commitHash)
  }

  private fun runGitCommand(
      subcommandName: String,
      subcommandArgs: List<String>,
      config: Map<String, String> = emptyMap(),
  ): String {
    val configArgs = config.flatMap { (key, value) -> listOf("-c", "$key=$value") }

    val fullArgs = configArgs + listOf(subcommandName) + subcommandArgs

    val result =
        processSpawner.runCaptured(
            executableHandle = gitExecutableHandle,
            workingDirectoryPath = path,
            args = fullArgs,
            env = emptyMap(),
        )

    if (result.exitCode != 0) {
      throw IllegalStateException(
          buildString {
            append("Git command failed in ")
            append(path)
            append(": git ")
            append(subcommandArgs.joinToString(" "))
            append(" (exit code ")
            append(result.exitCode)
            append(")")

            val trimmedOutput = result.output.trim()
            if (trimmedOutput.isNotEmpty()) {
              append(": ")
              append(trimmedOutput)
            }
          },
      )
    }

    return result.output
  }
}
