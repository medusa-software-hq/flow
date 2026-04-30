package software.medusa.git

import java.nio.file.Path
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

internal class GitCliRepository(
    private val repoPath: Path,
    private val gitExecutableHandle: ExecutableHandle,
    private val processSpawner: ProcessSpawner,
) : GitRepository {
  override fun commit(request: GitCommitRequest): GitCommitResult {
    require(request.message.isNotBlank()) { "Commit message must not be blank" }
    require(request.pathspecs.isNotEmpty()) { "Commit pathspecs must not be empty" }

    runGitCommand(args = listOf("add", "--") + request.pathspecs)
    runGitCommand(args = listOf("commit", "-m", request.message))

    val commitHash = runGitCommand(args = listOf("rev-parse", "HEAD")).trim()

    return GitCommitResult(commitHash = commitHash)
  }

  private fun runGitCommand(args: List<String>): String {
    val result =
        processSpawner.runCaptured(
            executableHandle = gitExecutableHandle,
            workingDirectoryPath = repoPath,
            args = args,
            env = emptyMap(),
        )

    if (result.exitCode != 0) {
      throw IllegalStateException(
          buildString {
            append("Git command failed in ")
            append(repoPath)
            append(": git ")
            append(args.joinToString(" "))
            append(" (exit code ")
            append(result.exitCode)
            append(")")

            val trimmedOutput = result.output.trim()
            if (trimmedOutput.isNotEmpty()) {
              append(": ")
              append(trimmedOutput)
            }
          }
      )
    }

    return result.output
  }
}
