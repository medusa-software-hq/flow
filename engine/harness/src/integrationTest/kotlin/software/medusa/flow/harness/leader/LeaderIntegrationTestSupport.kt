package software.medusa.flow.harness.leader

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult
import software.medusa.commons.openai_client.OaiTokenUsage
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator

/**
 * Shared plumbing for the leader/assistant engine's real-model integration tests (M3-10): a real,
 * minimal single-commit git repo to build a [GitWorktree] from (the only supported way to build one
 * — see [GitWorktree.load]), an in-memory [PhwWorkspaceAllocator] so no real toolchain/disk
 * workspace is needed, and a per-role token tally so a run's leader-vs-assistant (or
 * engine-vs-engine) cost can be logged and sanity-checked without any production cost-reporting
 * wiring (neither [software.medusa.flow.harness.HrsLeaderTaskCompleter] nor
 * [software.medusa.flow.harness.HrsProperTaskCompleter] populates
 * [software.medusa.flow.harness.HrsRunCost] today — only the Claude engine self-reports cost).
 */

/**
 * Writes [files] (path relative to the repo root, content) into a fresh temp directory, commits
 * them as a real git repo's initial commit, loads it as a [GitWorktree], runs [block], then deletes
 * the temp directory — whether or not [block] succeeds.
 */
suspend fun <T> withGitWorktree(
    files: Map<String, String>,
    block: suspend (GitWorktree) -> T,
): T {
  val directory =
      withContext(Dispatchers.IO) { Files.createTempDirectory("hrs-leader-integration-test") }
          .toFile()

  try {
    files.forEach { (path, content) ->
      val file = File(directory, path)
      file.parentFile?.mkdirs()
      file.writeText(content)
    }

    fun git(vararg args: String) {
      val process =
          ProcessBuilder(
                  "git",
                  "-c",
                  "user.name=Test",
                  "-c",
                  "user.email=test@example.com",
                  "-c",
                  "commit.gpgsign=false",
                  *args,
              )
              .directory(directory)
              .redirectErrorStream(true)
              .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      check(exitCode == 0) { "git ${args.joinToString(" ")} failed ($exitCode): $output" }
    }

    git("init", "-q")
    git("add", "-A")
    git("commit", "-q", "-m", "initial")

    val worktree =
        GitWorktree.load(
            repoDirectory = UfsNioDirectory(directoryPath = directory.toPath()),
            globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
        )

    return block(worktree)
  } finally {
    withContext(Dispatchers.IO) { directory.deleteRecursively() }
  }
}

/**
 * A [PhwWorkspaceAllocator] backed by an in-memory directory — no real disk, toolchain, or network
 * needed. Only usable with a [software.medusa.flow.universal_project.UnpProjectManifestLoader]
 * whose connections never call [PhwWorkspace.connectGradle]/[PhwWorkspace.connectNodeJs] (both
 * error out here), which is exactly the shape a lightweight, execution-based manifest gate needs.
 */
class InMemoryPhwWorkspaceAllocator : PhwWorkspaceAllocator {
  override suspend fun allocateWorkspace(): PhwWorkspace =
      object : PhwWorkspace {
        override val rootDirectory = UfsMemoryDirectory()

        override suspend fun connectNodeJs(
            packageManager: NjsPackageManager,
            packagePath: UfsLiteralAbsolutePath,
        ): NjsPackageConnection = error("not used by this test")

        override suspend fun connectGradle(
            projectPath: UfsLiteralAbsolutePath,
        ): GrdProjectConnection = error("not used by this test")

        override fun close() = Unit
      }
}

/** Accumulates [OaiTokenUsage] across every call a role's client makes over one run. */
class RoleTokenTally(
    val roleName: String,
) {
  var callCount = 0
    private set

  var promptTokens = 0
    private set

  var completionTokens = 0
    private set

  var totalTokens = 0
    private set

  fun record(
      usage: OaiTokenUsage,
  ) {
    callCount += 1
    promptTokens += usage.promptTokenCount
    completionTokens += usage.completionTokenCount
    totalTokens += usage.totalTokenCount
  }

  /** A machine-readable log line, in the same `key=value` style as this repo's other test logs. */
  fun logLine(): String =
      "role=$roleName calls=$callCount promptTokens=$promptTokens " +
          "completionTokens=$completionTokens totalTokens=$totalTokens"
}

/**
 * An [OaiConfiguredClient] decorator that tallies every response's [OaiTokenUsage] into [tally] —
 * the seam this repo's own [software.medusa.flow.harness.ai_system.HrsRetryingAiClient] decorates,
 * reused here for accounting instead of retries. A [OaiResult.NetworkError] or a response carrying
 * no usage (e.g. [OaiResponse.Corrupted]) is passed through untallied.
 */
class TallyingOaiConfiguredClient(
    private val delegate: OaiConfiguredClient,
    private val tally: RoleTokenTally,
) : OaiConfiguredClient {
  override suspend fun completeChat(
      chatHistory: OaiChatHistory,
      inferenceParams: OaiInferenceParams,
  ): OaiResult<OaiResponse> {
    val result = delegate.completeChat(chatHistory, inferenceParams)

    (result as? OaiResult.ResponseReceived)?.response?.let { response ->
      (response as? OaiResponse.Complete)?.tokenUsage?.let { usage -> tally.record(usage) }
    }

    return result
  }
}
