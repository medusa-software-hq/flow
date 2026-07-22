package software.medusa.flow.worker

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs `git` on `PATH` with a GitHub token supplied via `GIT_ASKPASS`, never argv, so it doesn't
 * show up in `ps` output. Shared by [WrkProcessGitCloner] (clone) and [WrkProperGitHubPublisher]
 * (branch/commit/push).
 */
internal object WrkGitProcess {
  data class Result(
      val exitCode: Int,
      val output: String,
  )

  /**
   * Runs `git`, throwing on a non-zero exit code. Returns combined stdout/stderr. [env] adds extra
   * environment variables (e.g. `GNUPGHOME` for a signing run) — passed by name, after the args.
   */
  suspend fun run(
      workingDirectory: File?,
      gitHubToken: suspend () -> String,
      vararg args: String,
      env: Map<String, String> = emptyMap(),
  ): String {
    val result = runAllowingFailure(workingDirectory, gitHubToken, *args, env = env)

    check(result.exitCode == 0) {
      "git ${args.joinToString(" ")} failed (exit ${result.exitCode}):\n${result.output}"
    }

    return result.output
  }

  /** Runs `git`, returning its exit code instead of throwing -- for callers that branch on it. */
  suspend fun runAllowingFailure(
      workingDirectory: File?,
      gitHubToken: suspend () -> String,
      vararg args: String,
      env: Map<String, String> = emptyMap(),
  ): Result {
    // Resolve a *current* token per invocation, so a refreshing supplier re-mints across the
    // several git calls a publish makes (checkout, add, commit, push) if one nears expiry.
    val token = gitHubToken()
    return withContext(Dispatchers.IO) {
      val askPassScript = writeAskPassScript()
      try {
        val process =
            ProcessBuilder(listOf("git") + args)
                .apply {
                  workingDirectory?.let { directory(it) }
                  environment()["GIT_ASKPASS"] = askPassScript.toString()
                  environment()["GIT_TERMINAL_PROMPT"] = "0"
                  environment()["WRK_GIT_ASKPASS_TOKEN"] = token
                  environment().putAll(env)
                  redirectErrorStream(true)
                }
                .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        Result(exitCode = exitCode, output = output)
      } finally {
        Files.deleteIfExists(askPassScript)
      }
    }
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
