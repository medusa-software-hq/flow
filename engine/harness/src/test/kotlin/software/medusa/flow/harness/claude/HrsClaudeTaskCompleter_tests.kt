package software.medusa.flow.harness.claude

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.ScoutingObserver
import software.medusa.flow.harness.HrsTaskCompleter.SolutionImplementationObserver
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskCompleter.WorkspaceBriefingObserver
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator

/**
 * Story A3: drives [HrsClaudeTaskCompleter] against a [FakeHrsClaudeProcess] feeding canned
 * stream-json sequences — no real `claude` binary is invoked. Covers the happy path (materialized
 * workspace + observed phases) and each of the driver's throw-based failure modes.
 */
class HrsClaudeTaskCompleter_tests {
  /** Records observed phases + the A5 agent-action/banner/cost hooks. */
  private class RecordingObserver : Observer {
    val phases = mutableListOf<String>()
    val agentActions = mutableListOf<String>()
    val banners = mutableListOf<HrsEngineBanner>()
    val costs = mutableListOf<HrsRunCost>()

    override fun observeAgentAction(
        summary: String,
    ) {
      agentActions += summary
    }

    override fun observeEngineBanner(
        banner: HrsEngineBanner,
    ) {
      banners += banner
    }

    override fun observeRunCost(
        cost: HrsRunCost,
    ) {
      costs += cost
    }

    override fun observeScouting(): ScoutingObserver = ScoutingObserver.Noop

    override fun observeSolutionImplementation(): SolutionImplementationObserver =
        SolutionImplementationObserver.Noop

    override fun observeWorkspaceBriefing(): WorkspaceBriefingObserver =
        WorkspaceBriefingObserver.Noop

    override fun observeImplementationPlan(
        implementationPlan: HrsExpertAiSystem.ImplementationPlan,
    ) = Unit

    override fun observePhase(
        phase: HrsPipelinePhase,
    ) {
      phases +=
          when (phase) {
            HrsPipelinePhase.WorkspacePreparing -> "WorkspacePreparing"
            HrsPipelinePhase.HealthGate -> "HealthGate"
            HrsPipelinePhase.Scouting -> "Scouting"
            HrsPipelinePhase.WorkspaceBriefing -> "WorkspaceBriefing"
            HrsPipelinePhase.ImplementationPlanning -> "ImplementationPlanning"
            is HrsPipelinePhase.ImplementationAttempt ->
                "ImplementationAttempt(${phase.attemptNumber}/${phase.maxAttempts})"
            is HrsPipelinePhase.HealthCheck -> "HealthCheck(${phase.attemptNumber})"
          }
    }
  }

  /**
   * Allocates a real on-disk workspace (a temp dir), as the driver requires an [UfsNioDirectory].
   */
  private inner class FakePhwWorkspaceAllocator : PhwWorkspaceAllocator {
    override suspend fun allocateWorkspace(): PhwWorkspace {
      val dir = createTempDirectory(prefix = "hrs-claude-workspace").toFile()
      allocatedDirs += dir
      return object : PhwWorkspace {
        override val rootDirectory = UfsNioDirectory(directoryPath = dir.toPath())

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
  }

  private val allocatedDirs = mutableListOf<File>()
  private var tempGitDir: File? = null

  @AfterTest
  fun cleanUp() {
    tempGitDir?.deleteRecursively()
    allocatedDirs.forEach { it.deleteRecursively() }
  }

  private fun config(): HrsClaudeEngineConfig =
      HrsClaudeEngineConfig(authEnvironment = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "fake-token"))

  private fun completeWith(
      claudeProcess: HrsClaudeProcess,
      observer: Observer = Observer.Noop,
  ): TaskCompletionResult = runBlocking {
    HrsClaudeTaskCompleter(
            physicalWorkspaceAllocator = FakePhwWorkspaceAllocator(),
            claudeProcess = claudeProcess,
            config = config(),
        )
        .completeTask(
            sourceGitWorktree = loadGitWorktree(),
            taskDescription = HrsTaskDescription(body = MdElement.Empty),
            observer = observer,
        )
  }

  @Test
  fun `a clean init-assistant-result run materializes the workspace and reports Success`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.SystemInit(
                        sessionId = "sess-1",
                        model = "claude-sonnet",
                        tools = listOf("Read", "Edit"),
                    ),
                    HrsClaudeMessage.Assistant(text = "done"),
                    HrsClaudeMessage.Result(
                        isError = false,
                        subtype = "success",
                        totalCostUsd = 0.01,
                        numTurns = 2,
                        durationMs = 1234,
                    ),
                ),
        )
    val observer = RecordingObserver()

    val result = completeWith(claudeProcess = process, observer = observer)

    val success = assertIs<TaskCompletionResult.Success>(result)
    // The source repo's README was copied into the allocated workspace.
    val workspaceRoot =
        (success.temporaryWorkspace.rootDirectory as UfsNioDirectory).directoryPath.toFile()
    assertTrue(File(workspaceRoot, "README.md").exists(), "workspace should be materialized")

    assertEquals(
        listOf("WorkspacePreparing", "ImplementationAttempt(1/1)"),
        observer.phases,
    )
    assertTrue(process.closed, "the run must be closed (process tree killed)")
  }

  @Test
  fun `the stream fires banner, agent actions, and run cost through the observer`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.SystemInit(
                        sessionId = "sess-1",
                        model = "claude-sonnet-4-6",
                        tools = listOf("Read", "Edit"),
                    ),
                    HrsClaudeMessage.Assistant(
                        text = "Editing the file now.\nsecond line ignored",
                        toolActions = listOf("edited `src/App.tsx`", "ran `gradle test`"),
                    ),
                    HrsClaudeMessage.Result(
                        isError = false,
                        subtype = "success",
                        totalCostUsd = 0.0421,
                        numTurns = 5,
                        durationMs = 8123,
                    ),
                ),
        )
    val observer = RecordingObserver()

    completeWith(claudeProcess = process, observer = observer)

    // init → banner (CLI version from config, model from the stream, A3 manifest-less mode).
    val banner = observer.banners.single()
    assertEquals("Claude Agent", banner.engineName)
    assertEquals("2.1.52 (Claude Code)", banner.cliVersion)
    assertEquals("claude-sonnet-4-6", banner.model)
    assertEquals(HrsEngineRunMode.ManifestLess, banner.runMode)

    // assistant text → one throttled narrative summary (first non-blank line), then tool actions.
    assertEquals(
        listOf("Editing the file now.", "edited `src/App.tsx`", "ran `gradle test`"),
        observer.agentActions,
    )

    // result → cost.
    val cost = observer.costs.single()
    assertEquals(HrsRunCost(totalCostUsd = 0.0421, numTurns = 5, durationMs = 8123), cost)
  }

  @Test
  fun `the driver builds the expected flags and injects only the auth env`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages = listOf(HrsClaudeMessage.Result(false, "success", null, null, null)),
        )

    completeWith(claudeProcess = process)

    val invocation = checkNotNull(process.lastInvocation)
    assertEquals(mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "fake-token"), invocation.environment)
    val args = invocation.arguments
    assertEquals("-p", args[0])
    assertContainsSubsequence(args, listOf("--output-format", "stream-json"))
    assertTrue(args.contains("--verbose"))
    assertContainsSubsequence(args, listOf("--setting-sources", "project"))
    assertContainsSubsequence(args, listOf("--permission-mode", "acceptEdits"))
    assertContainsSubsequence(args, listOf("--allowedTools", "Read Edit Write Bash Glob Grep"))
    assertContainsSubsequence(
        args,
        listOf("--disallowedTools", "Bash(git push:*) Bash(gh:*) WebFetch WebSearch"),
    )
    assertContainsSubsequence(args, listOf("--max-budget-usd", "0.5"))
  }

  @Test
  fun `an error result throws with the subtype and last assistant text`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.Assistant(text = "I could not finish"),
                    HrsClaudeMessage.Result(
                        isError = true,
                        subtype = "error_during_execution",
                        totalCostUsd = null,
                        numTurns = null,
                        durationMs = null,
                    ),
                ),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.ResultError, exception.kind)
    assertTrue(exception.message!!.contains("error_during_execution"))
    assertTrue(exception.message!!.contains("I could not finish"))
  }

  @Test
  fun `a budget-cap result throws naming the cap`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.Result(
                        isError = true,
                        subtype = "error_max_budget_usd",
                        totalCostUsd = 0.5,
                        numTurns = 7,
                        durationMs = 9999,
                    ),
                ),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.CapExceeded, exception.kind)
    assertTrue(exception.message!!.contains("error_max_budget_usd"))
  }

  @Test
  fun `a non-zero exit with no result throws with the stderr tail`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages = listOf(HrsClaudeMessage.SystemInit("s", "m", emptyList())),
            termination =
                HrsClaudeRun.Termination(exitCode = 137, standardError = "boom: killed by signal"),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.SubprocessFailure, exception.kind)
    assertTrue(exception.message!!.contains("137"))
    assertTrue(exception.message!!.contains("boom: killed by signal"))
  }

  @Test
  fun `an auth-indicating error result throws naming the personal rung`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.Result(
                        isError = true,
                        subtype = "error_authentication",
                        totalCostUsd = null,
                        numTurns = null,
                        durationMs = null,
                    ),
                ),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.AuthFailure, exception.kind)
    assertTrue(exception.message!!.contains("personal"))
  }

  private fun assertContainsSubsequence(
      actual: List<String>,
      expected: List<String>,
  ) {
    val index =
        (0..actual.size - expected.size).firstOrNull { start ->
          actual.subList(start, start + expected.size) == expected
        }
    assertTrue(index != null, "expected $expected as a contiguous subsequence of $actual")
  }

  /** A real, minimal single-commit git repo — [GitWorktree] can only be built via load. */
  private suspend fun loadGitWorktree(): GitWorktree {
    val dir = createTempDirectory(prefix = "hrs-claude-test").toFile()
    tempGitDir = dir

    File(dir, "README.md").writeText("hello")

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
              .directory(dir)
              .redirectErrorStream(true)
              .start()
      val output = process.inputStream.bufferedReader().readText()
      val exitCode = process.waitFor()
      check(exitCode == 0) { "git ${args.joinToString(" ")} failed ($exitCode): $output" }
    }

    git("init", "-q")
    git("add", "-A")
    git("commit", "-q", "-m", "initial")

    return GitWorktree.load(
        repoDirectory = UfsNioDirectory(directoryPath = dir.toPath()),
        globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
    )
  }
}
