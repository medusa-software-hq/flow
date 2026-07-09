package software.medusa.flow.worker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory

/**
 * Clones a repo's default branch over HTTPS using a GitHub token, via `git` on `PATH` (no clone
 * support in `medusa.commons:git`). The token is passed to the child process through its
 * environment (via `GIT_ASKPASS`), not argv, so it doesn't show up in `ps` output.
 */
class WrkProcessGitCloner(
    private val gitHubToken: String,
) : WrkGitCloner {
  override suspend fun cloneDefaultBranch(
      repoFullName: String,
      targetDirectory: Path,
  ): GitWorktree =
      withContext(Dispatchers.IO) {
        val askPassScript = writeAskPassScript()
        try {
          val process =
              ProcessBuilder(
                      "git",
                      "clone",
                      "--depth",
                      "1",
                      "https://x-access-token@github.com/$repoFullName.git",
                      targetDirectory.toString(),
                  )
                  .apply {
                    environment()["GIT_ASKPASS"] = askPassScript.toString()
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["WRK_GIT_ASKPASS_TOKEN"] = gitHubToken
                    redirectErrorStream(true)
                  }
                  .start()

          val output = process.inputStream.bufferedReader().readText()
          val exitCode = process.waitFor()

          check(exitCode == 0) { "git clone of $repoFullName failed (exit $exitCode):\n$output" }
        } finally {
          Files.deleteIfExists(askPassScript)
        }

        GitWorktree.load(
            repoDirectory = UfsNioDirectory(directoryPath = targetDirectory),
            globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
        )
      }

  private fun writeAskPassScript(): Path {
    val script = Files.createTempFile("flow-worker-askpass", ".sh")
    script.toFile().writeText("#!/bin/sh\necho \"\$WRK_GIT_ASKPASS_TOKEN\"\n")
    Files.setPosixFilePermissions(
        script,
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
    )
    return script
  }
}
