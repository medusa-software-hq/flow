package software.medusa.flow.harness.claude

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter
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
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader

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
    val engineWarnings = mutableListOf<String>()

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

    override fun observeEngineWarning(
        message: String,
    ) {
      engineWarnings += message
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
      projectManifestLoader: UnpProjectManifestLoader = UnusedProjectManifestLoader,
      withManifest: Boolean = false,
  ): TaskCompletionResult = runBlocking {
    HrsClaudeTaskCompleter(
            physicalWorkspaceAllocator = FakePhwWorkspaceAllocator(),
            projectManifestLoader = projectManifestLoader,
            claudeProcess = claudeProcess,
            config = config(),
        )
        .completeTask(
            sourceGitWorktree = loadGitWorktree(withManifest = withManifest),
            taskDescription =
                HrsTaskDescription(
                    body =
                        MdChapter.leaf(
                            title = MdInlineContent.of("Task"),
                            element = MdElement.Empty,
                        ),
                ),
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
    assertContainsSubsequence(
        args,
        listOf("--allowedTools", "Read Edit Write Bash Glob Grep Task"),
    )
    assertContainsSubsequence(
        args,
        listOf(
            "--disallowedTools",
            "Bash(git push:*) Bash(gh:*) WebFetch WebSearch AskUserQuestion",
        ),
    )
    assertContainsSubsequence(args, listOf("--max-budget-usd", "10.0"))
    assertContainsSubsequence(
        args,
        listOf("--append-system-prompt", HrsClaudeEngineConfig.defaultAppendSystemPrompt),
    )
  }

  @Test
  fun `a bounce also carries the append-system-prompt hints`() {
    val process =
        FakeHrsClaudeProcess.withRuns(
            cannedRuns = listOf(successRun("sess-1"), successRun("sess-1")),
        )

    completeWith(
        claudeProcess = process,
        projectManifestLoader =
            analyzeFailsOnCallLoader(failOnAnalyzeCall = 2, diagnostic = "boom"),
        withManifest = true,
    )

    assertEquals(2, process.spawnCount)
    process.invocations.forEach { invocation ->
      assertContainsSubsequence(
          invocation.arguments,
          listOf("--append-system-prompt", HrsClaudeEngineConfig.defaultAppendSystemPrompt),
      )
    }
  }

  @Test
  fun `a blank appendSystemPrompt omits the flag`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages = listOf(HrsClaudeMessage.Result(false, "success", null, null, null)),
        )

    runBlocking {
      HrsClaudeTaskCompleter(
              physicalWorkspaceAllocator = FakePhwWorkspaceAllocator(),
              projectManifestLoader = UnusedProjectManifestLoader,
              claudeProcess = process,
              config = config().copy(appendSystemPrompt = "  "),
          )
          .completeTask(
              sourceGitWorktree = loadGitWorktree(withManifest = false),
              taskDescription =
                  HrsTaskDescription(
                      body =
                          MdChapter.leaf(
                              title = MdInlineContent.of("Task"),
                              element = MdElement.Empty,
                          ),
                  ),
              observer = Observer.Noop,
          )
    }

    val args = checkNotNull(process.lastInvocation).arguments
    assertFalse(args.contains("--append-system-prompt"))
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

  @Test
  fun `a success result with a non-zero exit throws SubprocessFailure -- the process outcome is authoritative`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.Assistant(text = "looks done to me"),
                    HrsClaudeMessage.Result(
                        isError = false,
                        subtype = "success",
                        totalCostUsd = 0.01,
                        numTurns = 2,
                        durationMs = 100,
                    ),
                ),
            termination =
                HrsClaudeRun.Termination(
                    exitCode = 1,
                    standardError = "Oops, something went wrong",
                ),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.SubprocessFailure, exception.kind)
    assertTrue(exception.message!!.contains("1"))
    assertTrue(exception.message!!.contains("Oops, something went wrong"))
    assertTrue(exception.message!!.contains("looks done to me"))
  }

  @Test
  fun `a non-zero exit still prefers a cap or auth subtype over the generic process failure`() {
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
            termination = HrsClaudeRun.Termination(exitCode = 1, standardError = ""),
        )

    val exception =
        assertFailsWith<HrsClaudeEngineException> { completeWith(claudeProcess = process) }
    assertEquals(HrsClaudeEngineException.Kind.CapExceeded, exception.kind)
  }

  @Test
  fun `exit 0 plus a success result but non-empty stderr succeeds and emits an engine warning`() {
    val process =
        FakeHrsClaudeProcess(
            cannedMessages =
                listOf(
                    HrsClaudeMessage.Result(
                        isError = false,
                        subtype = "success",
                        totalCostUsd = 0.01,
                        numTurns = 1,
                        durationMs = 50,
                    ),
                ),
            termination =
                HrsClaudeRun.Termination(exitCode = 0, standardError = "a harmless warning"),
        )
    val observer = RecordingObserver()

    val result = completeWith(claudeProcess = process, observer = observer)

    assertIs<TaskCompletionResult.Success>(result)
    assertEquals(listOf("a harmless warning"), observer.engineWarnings)
  }

  // ---------------------------------------------------------------------------------------------
  // A4: manifest probe → gated vs manifest-less mode, and the post-run gate/bounce loop.
  // ---------------------------------------------------------------------------------------------

  private companion object {
    private val rootModulePath: UfsLiteralAbsolutePath =
        checkNotNull(UfsAbsolutePath.parse("/").toLiteral())

    /** A successful init→result stream for one `claude` run, with the given session id. */
    private fun successRun(
        sessionId: String,
    ): List<HrsClaudeMessage> =
        listOf(
            HrsClaudeMessage.SystemInit(
                sessionId = sessionId,
                model = "claude-sonnet",
                tools = listOf("Read", "Edit"),
            ),
            HrsClaudeMessage.Assistant(text = "done"),
            HrsClaudeMessage.Result(
                isError = false,
                subtype = "success",
                totalCostUsd = 0.01,
                numTurns = 2,
                durationMs = 100,
            ),
        )
  }

  /**
   * Should never be loaded — used where the manifest probe is expected to skip loading entirely.
   */
  private object UnusedProjectManifestLoader : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest = error("the manifest loader must not be called in manifest-less mode")
  }

  /** Wraps [connection] as the sole module of a manifest at the root path. */
  private class SingleModuleManifest(
      private val connection: UnpModuleConnection,
  ) : UnpModuleManifest {
    override suspend fun connect(
        physicalWorkspace: PhwWorkspace,
        modulePath: UfsLiteralAbsolutePath,
    ): UnpModuleConnection = connection

    companion object {
      fun asProjectManifest(
          connection: UnpModuleConnection,
      ): UnpProjectManifest =
          UnpProjectManifest(
              moduleManifestByPath = mapOf(rootModulePath to SingleModuleManifest(connection)),
          )
    }
  }

  /** A loader whose single module is scripted by the given [connection] factory. */
  private class ScriptedProjectManifestLoader(
      private val connectionFactory: () -> UnpModuleConnection,
  ) : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest = SingleModuleManifest.asProjectManifest(connection = connectionFactory())
  }

  /** Every lifecycle phase always succeeds — the gate passes initially and after every run. */
  private fun alwaysHealthyLoader() = ScriptedProjectManifestLoader {
    object : UnpModuleConnection {
      override suspend fun bootstrap() = UnpModuleConnection.Result.Success

      override suspend fun analyze() = UnpModuleConnection.Result.Success

      override suspend fun test() = UnpModuleConnection.Result.Success

      override suspend fun normalize() = UnpModuleConnection.Result.Success
    }
  }

  /** `analyze` fails on exactly its [failOnAnalyzeCall]-th call; passes otherwise. */
  private fun analyzeFailsOnCallLoader(
      failOnAnalyzeCall: Int,
      diagnostic: String,
  ) = ScriptedProjectManifestLoader {
    object : UnpModuleConnection {
      private var analyzeCalls = 0

      override suspend fun bootstrap() = UnpModuleConnection.Result.Success

      override suspend fun analyze(): UnpModuleConnection.Result {
        analyzeCalls += 1
        return if (analyzeCalls == failOnAnalyzeCall) {
          UnpModuleConnection.Result.Failure(diagnosticOutput = diagnostic)
        } else {
          UnpModuleConnection.Result.Success
        }
      }

      override suspend fun test() = UnpModuleConnection.Result.Success

      override suspend fun normalize() = UnpModuleConnection.Result.Success
    }
  }

  /**
   * Passes `analyze` only on the initial gate (call 1); fails every call after — never recovers.
   */
  private fun passesGateThenAlwaysUnhealthyLoader(
      diagnostic: String,
  ) = ScriptedProjectManifestLoader {
    object : UnpModuleConnection {
      private var analyzeCalls = 0

      override suspend fun bootstrap() = UnpModuleConnection.Result.Success

      override suspend fun analyze(): UnpModuleConnection.Result {
        analyzeCalls += 1
        return if (analyzeCalls == 1) {
          UnpModuleConnection.Result.Success
        } else {
          UnpModuleConnection.Result.Failure(diagnosticOutput = diagnostic)
        }
      }

      override suspend fun test() = UnpModuleConnection.Result.Success

      override suspend fun normalize() = UnpModuleConnection.Result.Success
    }
  }

  /** `analyze` always fails — the initial gate never passes. */
  private fun brokenBaselineLoader(
      diagnostic: String,
  ) = ScriptedProjectManifestLoader {
    object : UnpModuleConnection {
      override suspend fun bootstrap() = UnpModuleConnection.Result.Success

      override suspend fun analyze() =
          UnpModuleConnection.Result.Failure(diagnosticOutput = diagnostic)

      override suspend fun test() = UnpModuleConnection.Result.Success

      override suspend fun normalize() = UnpModuleConnection.Result.Success
    }
  }

  @Test
  fun `gated-green - manifest present and the gate passes before and after the run`() {
    val process = FakeHrsClaudeProcess.withRuns(cannedRuns = listOf(successRun("sess-1")))
    val observer = RecordingObserver()

    val result =
        completeWith(
            claudeProcess = process,
            observer = observer,
            projectManifestLoader = alwaysHealthyLoader(),
            withManifest = true,
        )

    assertIs<TaskCompletionResult.Success>(result)
    // Exactly one claude run (no bounce), and the banner reports Gated mode.
    assertEquals(1, process.spawnCount)
    assertEquals(HrsEngineRunMode.Gated, observer.banners.single().runMode)
    assertEquals(
        listOf(
            "WorkspacePreparing",
            "HealthGate",
            "ImplementationAttempt(1/3)",
            "HealthCheck(1)",
        ),
        observer.phases,
    )
  }

  @Test
  fun `gated-red-recovered - a red post-run gate bounces via resume and then goes green`() {
    val diagnostic = "analyze boom on module"
    val process =
        FakeHrsClaudeProcess.withRuns(
            cannedRuns = listOf(successRun("sess-1"), successRun("sess-1")),
        )
    val observer = RecordingObserver()

    // analyze call 1 = initial gate (pass), call 2 = post-run gate #1 (fail), call 3 = post-run
    // gate #2 after the bounce (pass).
    val result =
        completeWith(
            claudeProcess = process,
            observer = observer,
            projectManifestLoader =
                analyzeFailsOnCallLoader(failOnAnalyzeCall = 2, diagnostic = diagnostic),
            withManifest = true,
        )

    assertIs<TaskCompletionResult.Success>(result)
    assertEquals(2, process.spawnCount)

    // The 2nd (resume) invocation carries --resume <sessionId> and the diagnostics in its prompt.
    val resumeArgs = process.invocations[1].arguments
    assertContainsSubsequence(resumeArgs, listOf("--resume", "sess-1"))
    val resumePrompt = resumeArgs[resumeArgs.indexOf("-p") + 1]
    assertTrue(
        resumePrompt.contains(diagnostic),
        "the resume prompt must carry the failing module's diagnostics: $resumePrompt",
    )

    val attemptPhases = observer.phases.filter { it.startsWith("ImplementationAttempt") }
    assertEquals(
        listOf("ImplementationAttempt(1/3)", "ImplementationAttempt(2/3)"),
        attemptPhases,
    )
  }

  @Test
  fun `bounce-exhausted - the gate stays red through the whole budget`() {
    val process =
        FakeHrsClaudeProcess.withRuns(
            cannedRuns = listOf(successRun("sess-1"), successRun("sess-1"), successRun("sess-1")),
        )
    val observer = RecordingObserver()

    val result =
        completeWith(
            claudeProcess = process,
            observer = observer,
            projectManifestLoader =
                passesGateThenAlwaysUnhealthyLoader(diagnostic = "still broken"),
            withManifest = true,
        )

    val failure = assertIs<TaskCompletionResult.Failure.AttemptsExhausted>(result)
    // Initial run + 2 bounces = 3 attempts (bounceBudget = 2).
    assertEquals(3, failure.attemptsMade)
    assertEquals(3, process.spawnCount)
    assertTrue(
        failure.lastHealthStatus.failureReport.failure.failureByModulePath.values.any {
          it.diagnosticOutput == "still broken"
        },
        "the final failure must carry the last diagnostics",
    )

    // Regression: the bounce prompt actually handed to `claude -p …` must not begin with `/`, or
    // Claude Code reads its first line as a slash command and discards the whole diagnostic. The
    // sole module's path here is the root `/`, so an unguarded prompt began `/:` and was swallowed
    // as `Unknown command: /:`, silently no-op'ing the bounce loop.
    val bouncePrompt = process.invocations[1].arguments.single { it.contains("still broken") }
    assertFalse(
        bouncePrompt.startsWith("/"),
        "the bounce prompt must not begin with '/' or Claude Code eats it as a slash command",
    )
  }

  @Test
  fun `broken baseline - the initial gate is red so claude is never spawned`() {
    val process = FakeHrsClaudeProcess.withRuns(cannedRuns = listOf(successRun("sess-1")))
    val observer = RecordingObserver()

    val result =
        completeWith(
            claudeProcess = process,
            observer = observer,
            projectManifestLoader = brokenBaselineLoader(diagnostic = "baseline broken"),
            withManifest = true,
        )

    val failure = assertIs<TaskCompletionResult.Failure.JointOperation>(result)
    assertEquals(HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis, failure.phase)
    assertEquals(0, process.spawnCount, "claude must never run on a broken baseline")
    assertEquals(listOf("WorkspacePreparing", "HealthGate"), observer.phases)
  }

  @Test
  fun `manifest-less - no project_yaml means no gate, one run, and an augmented prompt`() {
    val process = FakeHrsClaudeProcess.withRuns(cannedRuns = listOf(successRun("sess-1")))
    val observer = RecordingObserver()

    val result =
        completeWith(
            claudeProcess = process,
            observer = observer,
            projectManifestLoader = UnusedProjectManifestLoader,
            withManifest = false,
        )

    assertIs<TaskCompletionResult.Success>(result)
    assertEquals(1, process.spawnCount)
    assertEquals(HrsEngineRunMode.ManifestLess, observer.banners.single().runMode)
    // No gate phases (no HealthGate / HealthCheck).
    assertEquals(listOf("WorkspacePreparing", "ImplementationAttempt(1/1)"), observer.phases)

    val args = checkNotNull(process.lastInvocation).arguments
    val prompt = args[args.indexOf("-p") + 1]
    assertTrue(
        prompt.contains("run the repository's own checks"),
        "the manifest-less prompt must instruct claude to run the repo's own checks: $prompt",
    )
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

  /**
   * A real, minimal single-commit git repo — [GitWorktree] can only be built via load. When
   * [withManifest] is set it also commits a `project.yaml`, so the copied workspace has one and the
   * completer's manifest probe selects gated mode.
   */
  private suspend fun loadGitWorktree(
      withManifest: Boolean = false,
  ): GitWorktree {
    val dir = createTempDirectory(prefix = "hrs-claude-test").toFile()
    tempGitDir = dir

    File(dir, "README.md").writeText("hello")
    if (withManifest) File(dir, "project.yaml").writeText("modules: []\n")

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
