package software.medusa.flow.harness

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.assistance.FakeHrsAssistant
import software.medusa.flow.harness.assistance.FakeHrsToolbox
import software.medusa.flow.harness.assistance.HrsAssistanceContext
import software.medusa.flow.harness.assistance.HrsAssistant
import software.medusa.flow.harness.assistance.HrsChunkSummarizer
import software.medusa.flow.harness.assistance.HrsToolbox
import software.medusa.flow.harness.assistance.HrsToolboxFactory
import software.medusa.flow.harness.history.HrsChunkConfig
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.history.HrsLeaderHistoryRendering.renderLeaderHistory
import software.medusa.flow.harness.leadership.FakeHrsLeader
import software.medusa.flow.harness.leadership.HrsLeader
import software.medusa.flow.harness.leadership.HrsLeaderCommand
import software.medusa.flow.harness.leadership.HrsLeaderContext
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Covers story 06's deliverable: [HrsLeaderTaskCompleter] wires the leader (story 05) and assistant
 * (story 03, faked here via [FakeHrsLeader]/[FakeHrsAssistant] — built for exactly this purpose)
 * into the turn grammar — leader never asked twice without a delegation in between, the outer
 * delegation log growing by one `(taskDefinition, report)` entry per delegation, the hide-list
 * applied mechanically before each delegation — plus the shared early pipeline (identical to
 * [HrsProperTaskCompleter]'s) and the delegation-budget / leader-failure paths funneling into the
 * exact same [TaskCompletionResult.Failure.AttemptsExhausted] shape the builtin engine uses.
 */
class HrsLeaderTaskCompleter_tests {
  private companion object {
    private val rootModulePath: UfsLiteralAbsolutePath =
        checkNotNull(UfsAbsolutePath.parse("/").toLiteral())

    private val anyTaskDescription =
        HrsTaskDescription(
            body = MdChapter.leaf(title = MdInlineContent.of("Task"), element = MdElement.Empty),
        )

    private val sampleReport =
        HrsDelegationReport(
            outcome = HrsDelegationOutcome.Done,
            narrative = "did the thing",
            filesTouched = "- `/a.txt` — edited",
            bufferChanges = "none",
            checksSummary = "green",
        )

    private fun taskDefinition(
        label: String,
    ): HrsTaskDefinition = HrsTaskDefinition(markdown = "Do $label.")

    private fun delegateResult(
        command: HrsLeaderCommand,
    ): HrsLeader.Result = HrsLeader.Result.Decided(command = command)

    private val stopResult = delegateResult(HrsLeaderCommand.Stop)

    private fun labeled(
        entity: VedEntity,
    ): VedExpandedDirectory.LabeledEntity =
        VedExpandedDirectory.LabeledEntity(
            status = GitWorktreeEntity.Status.included,
            entity = entity,
        )

    private fun fileContent(
        text: String,
    ): TxtFileContent = TxtFileContent(content = TxtBlock.of(text))

    /** A single-file worktree: `/exposed.txt`, already open and exposed on the leader's board. */
    private fun worktreeWithExposedFile(
        marker: String,
    ): VedWorktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory(
                    labeledEntityByName =
                        mapOf(
                            UfsName.Literal("exposed.txt") to
                                labeled(
                                    VedOpenedFile.of(
                                            content = fileContent(marker),
                                            timestamp = VedTimestamp(1),
                                        )
                                        .withExposure(VedExposure.Exposed),
                                ),
                        ),
                ),
        )
  }

  private object NeverCalledLeader : HrsLeader {
    override suspend fun decide(
        context: HrsLeaderContext,
    ): HrsLeader.Result = error("the leader must not be called when the initial gate fails")
  }

  private object NeverCalledAssistant : HrsAssistant {
    override suspend fun runDelegation(
        context: HrsAssistanceContext,
        taskDefinition: HrsTaskDefinition,
        toolbox: HrsToolbox,
    ): HrsAssistant.Result = error("the assistant must not be called when the initial gate fails")
  }

  /** Records every [create] call, in order, and always returns a fresh [FakeHrsToolbox]. */
  private class RecordingHrsToolboxFactory : HrsToolboxFactory {
    data class Invocation(
        val projectConnection: UnpProjectConnection,
        val delegationTimestamp: VedTimestamp,
    )

    val invocations: MutableList<Invocation> = mutableListOf()

    override fun create(
        gitWorktree: GitWorktree,
        physicalRootDirectory: UfsMutableDirectory,
        projectConnection: UnpProjectConnection,
        delegationTimestamp: VedTimestamp,
    ): HrsToolbox {
      invocations +=
          Invocation(
              projectConnection = projectConnection,
              delegationTimestamp = delegationTimestamp,
          )
      return FakeHrsToolbox()
    }
  }

  private val fixedToolboxFactory = HrsToolboxFactory { _, _, _, _ -> FakeHrsToolbox() }

  /**
   * A scripted [HrsChunkSummarizer]: answers [summaryByKind] (or degrades to `null` for a kind not
   * listed) and records every [summarize] call, in order, in [calls] — for asserting exactly which
   * chunk-close requests the executor made and with what delegation range.
   */
  private class FakeHrsChunkSummarizer(
      private val summaryByKind: Map<HrsChunkSummaryKind, HrsChunkSummary> = emptyMap(),
  ) : HrsChunkSummarizer {
    val calls: MutableList<Pair<IntRange, HrsChunkSummaryKind>> = mutableListOf()

    override suspend fun summarize(
        delegationRange: IntRange,
        kind: HrsChunkSummaryKind,
    ): HrsChunkSummary? {
      calls += delegationRange to kind
      return summaryByKind[kind]
    }
  }

  /** Delegates everything to [Observer.Noop] except [observeCompaction], which it records. */
  private class RecordingObserver : Observer by Observer.Noop {
    data class Compaction(
        val kind: HrsChunkSummaryKind,
        val delegationRange: IntRange,
        val summary: HrsChunkSummary,
    )

    val compactions: MutableList<Compaction> = mutableListOf()

    override fun observeCompaction(
        kind: HrsChunkSummaryKind,
        delegationRange: IntRange,
        summary: HrsChunkSummary,
    ) {
      compactions += Compaction(kind = kind, delegationRange = delegationRange, summary = summary)
    }
  }

  /** No modules — every lifecycle phase (bootstrap/analyze/test) trivially succeeds, every call. */
  private object EmptyProjectManifestLoader : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest = UnpProjectManifest(moduleManifestByPath = emptyMap())
  }

  /** Wraps [connection] as the sole module of a manifest at [rootModulePath]. */
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

  /** A single module whose `analyze` always fails — the initial health gate never passes. */
  private object AlwaysUnhealthyProjectManifestLoader : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest =
        SingleModuleManifest.asProjectManifest(
            connection =
                object : UnpModuleConnection {
                  override suspend fun bootstrap() = UnpModuleConnection.Result.Success

                  override suspend fun analyze() =
                      UnpModuleConnection.Result.Failure(diagnosticOutput = "broken")

                  override suspend fun test() = UnpModuleConnection.Result.Success

                  override suspend fun normalize() = UnpModuleConnection.Result.Success
                },
        )
  }

  /**
   * A single module that passes `analyze` exactly once (the initial gate) and fails every call
   * after — used to reach the lead loop healthily, then have the *final* health check (run once the
   * lead loop gives up) come back unhealthy.
   */
  private object PassesInitialGateThenAlwaysUnhealthyProjectManifestLoader :
      UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest {
      var gatePassed = false

      return SingleModuleManifest.asProjectManifest(
          connection =
              object : UnpModuleConnection {
                override suspend fun bootstrap() = UnpModuleConnection.Result.Success

                override suspend fun analyze(): UnpModuleConnection.Result {
                  if (!gatePassed) {
                    gatePassed = true
                    return UnpModuleConnection.Result.Success
                  }
                  return UnpModuleConnection.Result.Failure(diagnosticOutput = "still broken")
                }

                override suspend fun test() = UnpModuleConnection.Result.Success

                override suspend fun normalize() = UnpModuleConnection.Result.Success
              },
      )
    }
  }

  private class FakePhwWorkspaceAllocator : PhwWorkspaceAllocator {
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

  private var tempGitDir: File? = null

  @AfterTest
  fun cleanUp() {
    tempGitDir?.deleteRecursively()
  }

  /**
   * A real, minimal single-commit git repo carrying `README.md` and `exposed.txt` — [GitWorktree]
   * can only be built via [GitWorktree.load], and the hide-list mechanics need a real tracked file
   * to hide.
   */
  private suspend fun loadGitWorktree(): GitWorktree {
    val dir = createTempDirectory(prefix = "hrs-leader-task-completer-test").toFile()
    tempGitDir = dir

    File(dir, "README.md").writeText("hello")
    File(dir, "exposed.txt").writeText("original")

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

  private fun buildTaskCompleter(
      projectManifestLoader: UnpProjectManifestLoader,
      leader: HrsLeader,
      assistant: HrsAssistant,
      toolboxFactory: HrsToolboxFactory = fixedToolboxFactory,
      maxDelegations: Int = HrsLeaderTaskCompleter.defaultMaxDelegations,
      chunkConfig: HrsChunkConfig = HrsChunkConfig.default,
  ): HrsLeaderTaskCompleter =
      HrsLeaderTaskCompleter(
          physicalWorkspaceAllocator = FakePhwWorkspaceAllocator(),
          projectManifestLoader = projectManifestLoader,
          leader = leader,
          assistant = assistant,
          toolboxFactory = toolboxFactory,
          maxDelegations = maxDelegations,
          chunkConfig = chunkConfig,
      )

  @Test
  fun `an immediate Stop succeeds without ever calling the assistant`() = runBlocking {
    val leader = FakeHrsLeader(results = listOf(stopResult))
    val assistant =
        FakeHrsAssistant(
            result = HrsAssistant.Result(sampleReport, VedWorktree.import(loadGitWorktree()))
        )
    val taskCompleter =
        buildTaskCompleter(
            projectManifestLoader = EmptyProjectManifestLoader,
            leader = leader,
            assistant = assistant,
        )

    val result =
        taskCompleter.completeTask(
            sourceGitWorktree = loadGitWorktree(),
            taskDescription = anyTaskDescription,
            observer = Observer.Noop,
        )

    assertIs<TaskCompletionResult.Success>(result)
    assertEquals(1, leader.invocations.size)
    assertEquals(0, leader.invocations.single().delegationLog.size)
    assertTrue(assistant.invocations.isEmpty())
  }

  @Test
  fun `the turn grammar alternates leader and assistant, growing the log by one entry per delegation`() =
      runBlocking {
        val tasks =
            listOf(taskDefinition("first"), taskDefinition("second"), taskDefinition("third"))

        val leader =
            FakeHrsLeader(
                results =
                    tasks.map { task ->
                      delegateResult(HrsLeaderCommand.Delegate(taskDefinition = task))
                    } + stopResult,
            )

        val finalWorktree = VedWorktree.import(loadGitWorktree())
        val assistant = FakeHrsAssistant(result = HrsAssistant.Result(sampleReport, finalWorktree))

        val toolboxFactory = RecordingHrsToolboxFactory()

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                toolboxFactory = toolboxFactory,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)

        // 4 leader calls (one per delegation, plus the final Stop); exactly 3 assistant calls
        // (never a Delegate without a matching thread, never a leader call skipped).
        assertEquals(4, leader.invocations.size)
        assertEquals(3, assistant.invocations.size)

        // Every leader call sees the log exactly one entry longer than the call before it — the
        // grammar never asks the leader twice without a delegation closing the gap in between.
        assertEquals(listOf(0, 1, 2, 3), leader.invocations.map { it.delegationLog.size })

        // Each delegation's task definition round-trips into the assistant call, and the assistant
        // sees the same pre-delegation log the leader that ordered it just saw.
        assertEquals(tasks, assistant.invocations.map { it.taskDefinition })
        assertEquals(listOf(0, 1, 2), assistant.invocations.map { it.context.delegationLog.size })

        // The final leader call's log carries all three closed (taskDefinition, report) entries.
        val finalLog = leader.invocations.last().delegationLog
        assertEquals(tasks, finalLog.entries.map { it.taskDefinition })
        assertEquals(List(3) { sampleReport }, finalLog.entries.map { it.report })

        // Each delegation's toolbox is stamped with its own index as the timestamp (0, 1, 2) — the
        // axis the journal zips file snapshots against.
        assertEquals(listOf(0, 1, 2), toolboxFactory.invocations.map { it.delegationTimestamp.t })
      }

  @Test
  fun `a hide-list entry is applied mechanically before the next delegation, no leader round spent`() =
      runBlocking {
        val exposedMarker = "ZZEXPOSEDZZ"

        val leader =
            FakeHrsLeader(
                results =
                    listOf(
                        delegateResult(
                            HrsLeaderCommand.Delegate(taskDefinition = taskDefinition("first")),
                        ),
                        delegateResult(
                            HrsLeaderCommand.Delegate(
                                taskDefinition = taskDefinition("second"),
                                hideList = listOf("/exposed.txt"),
                            ),
                        ),
                        stopResult,
                    ),
            )

        val assistant =
            FakeHrsAssistant(
                result = HrsAssistant.Result(sampleReport, worktreeWithExposedFile(exposedMarker)),
            )

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)
        assertEquals(2, assistant.invocations.size)

        fun exposureOf(
            worktree: VedWorktree,
        ): VedExposure =
            (worktree.rootDirectory.labeledEntityByName
                    .getValue(UfsName.Literal("exposed.txt"))
                    .entity as VedOpenedFile)
                .exposure

        // The first delegation's own input worktree is freshly imported from git: `/exposed.txt` is
        // tracked (so the later hide can resolve it against real git content) but not yet open.
        assertTrue(
            assistant.invocations[0]
                .context
                .worktree
                .rootDirectory
                .labeledEntityByName
                .getValue(UfsName.Literal("exposed.txt"))
                .entity !is VedOpenedFile,
        )

        // The second delegation's context worktree is the *first* delegation's output worktree
        // (which has `/exposed.txt` exposed) with the requested hide already mechanically applied —
        // no leader round was spent deciding it.
        assertEquals(VedExposure.Hidden, exposureOf(assistant.invocations[1].context.worktree))
      }

  @Test
  fun `an initial health-gate failure returns the same JointOperation shape as the builtin engine, without ever calling the leader`() =
      runBlocking {
        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = AlwaysUnhealthyProjectManifestLoader,
                leader = NeverCalledLeader,
                assistant = NeverCalledAssistant,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        val failure = assertIs<TaskCompletionResult.Failure.JointOperation>(result)
        assertEquals(HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis, failure.phase)
      }

  @Test
  fun `exhausting the delegation budget while the project is healthy still succeeds`() =
      runBlocking {
        val leader =
            FakeHrsLeader(
                results =
                    listOf(
                        delegateResult(
                            HrsLeaderCommand.Delegate(taskDefinition = taskDefinition("again"))
                        ),
                    ),
            )
        val assistant =
            FakeHrsAssistant(
                result = HrsAssistant.Result(sampleReport, VedWorktree.import(loadGitWorktree()))
            )

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                maxDelegations = 3,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)
        assertEquals(3, leader.invocations.size)
        assertEquals(3, assistant.invocations.size)
      }

  @Test
  fun `exhausting the delegation budget while the project is unhealthy fails with the shared AttemptsExhausted shape`() =
      runBlocking {
        val leader =
            FakeHrsLeader(
                results =
                    listOf(
                        delegateResult(
                            HrsLeaderCommand.Delegate(taskDefinition = taskDefinition("again")),
                        ),
                    ),
            )
        val assistant =
            FakeHrsAssistant(
                result = HrsAssistant.Result(sampleReport, VedWorktree.import(loadGitWorktree())),
            )

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = PassesInitialGateThenAlwaysUnhealthyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                maxDelegations = 2,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        val failure = assertIs<TaskCompletionResult.Failure.AttemptsExhausted>(result)
        assertEquals(2, failure.attemptsMade)
      }

  @Test
  fun `the leader failing to decide ends the run through the same final-health-check path as budget exhaustion`() =
      runBlocking {
        val leader = FakeHrsLeader(results = listOf(HrsLeader.Result.Failed(reason = "stuck")))
        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = PassesInitialGateThenAlwaysUnhealthyProjectManifestLoader,
                leader = leader,
                assistant = NeverCalledAssistant,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        val failure = assertIs<TaskCompletionResult.Failure.AttemptsExhausted>(result)
        // No delegation ever ran — the leader failed on its very first turn.
        assertEquals(0, failure.attemptsMade)
      }

  @Test
  fun `a small chunk close asks the just-finished thread's chunk summarizer and stores what it returns, firing observeCompaction`() =
      runBlocking {
        val chunkConfig = HrsChunkConfig(smallChunkSize = 3, bigChunkSize = 8)
        val tasks =
            listOf(taskDefinition("first"), taskDefinition("second"), taskDefinition("third"))

        val leader =
            FakeHrsLeader(
                results =
                    tasks.map { task ->
                      delegateResult(HrsLeaderCommand.Delegate(taskDefinition = task))
                    } + stopResult,
            )

        val summary = HrsChunkSummary("zzsmallsummaryzz")
        val chunkSummarizer =
            FakeHrsChunkSummarizer(summaryByKind = mapOf(HrsChunkSummaryKind.SmallChunk to summary))
        val finalWorktree = VedWorktree.import(loadGitWorktree())
        val assistant =
            FakeHrsAssistant(
                result =
                    HrsAssistant.Result(
                        report = sampleReport,
                        finalWorktree = finalWorktree,
                        chunkSummarizer = chunkSummarizer,
                    ),
            )

        val observer = RecordingObserver()

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                chunkConfig = chunkConfig,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = observer,
            )

        assertIs<TaskCompletionResult.Success>(result)

        // Exactly one summarize call — for the small chunk (t=0..2) that closed on the 3rd
        // delegation — never re-requested on the delegations that came before it closed.
        assertEquals(listOf(0..2 to HrsChunkSummaryKind.SmallChunk), chunkSummarizer.calls)

        val finalLog = leader.invocations.last().delegationLog
        assertEquals(summary, finalLog.smallChunkSummaries[0])
        assertTrue(finalLog.bigChunkSummaries.isEmpty())

        assertEquals(1, observer.compactions.size)
        val compaction = observer.compactions.single()
        assertEquals(HrsChunkSummaryKind.SmallChunk, compaction.kind)
        assertEquals(0..2, compaction.delegationRange)
        assertEquals(summary, compaction.summary)
      }

  @Test
  fun `a big chunk close cascades from its last small chunk close, summarizing the whole big-chunk range from full contents`() =
      runBlocking {
        // s=1, B=2: each delegation closes its own small chunk; the 2nd delegation's small-chunk
        // close is also the 1st big chunk's close.
        val chunkConfig = HrsChunkConfig(smallChunkSize = 1, bigChunkSize = 2)
        val tasks = listOf(taskDefinition("first"), taskDefinition("second"))

        val leader =
            FakeHrsLeader(
                results =
                    tasks.map { task ->
                      delegateResult(HrsLeaderCommand.Delegate(taskDefinition = task))
                    } + stopResult,
            )

        val smallSummary = HrsChunkSummary("zzsmallzz")
        val bigSummary = HrsChunkSummary("zzbigzz")
        val chunkSummarizer =
            FakeHrsChunkSummarizer(
                summaryByKind =
                    mapOf(
                        HrsChunkSummaryKind.SmallChunk to smallSummary,
                        HrsChunkSummaryKind.BigChunk to bigSummary,
                    ),
            )
        val finalWorktree = VedWorktree.import(loadGitWorktree())
        val assistant =
            FakeHrsAssistant(
                result =
                    HrsAssistant.Result(
                        report = sampleReport,
                        finalWorktree = finalWorktree,
                        chunkSummarizer = chunkSummarizer,
                    ),
            )

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                chunkConfig = chunkConfig,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)

        // Delegation 0 closes only small chunk 0 (t=0..0); delegation 1 closes small chunk 1
        // (t=1..1) AND big chunk 0 — requested over the *whole* big-chunk range (t=0..1), never
        // just the last small chunk and never derived from the small summaries already stored.
        assertEquals(
            listOf(
                0..0 to HrsChunkSummaryKind.SmallChunk,
                1..1 to HrsChunkSummaryKind.SmallChunk,
                0..1 to HrsChunkSummaryKind.BigChunk,
            ),
            chunkSummarizer.calls,
        )

        val finalLog = leader.invocations.last().delegationLog
        assertEquals(mapOf(0 to smallSummary, 1 to smallSummary), finalLog.smallChunkSummaries)
        assertEquals(mapOf(0 to bigSummary), finalLog.bigChunkSummaries)
      }

  @Test
  fun `a chunk close whose summarizer degrades to null stores nothing and never blocks the run`() =
      runBlocking {
        val chunkConfig = HrsChunkConfig(smallChunkSize = 1, bigChunkSize = 8)
        val leader =
            FakeHrsLeader(
                results =
                    listOf(
                        delegateResult(
                            HrsLeaderCommand.Delegate(taskDefinition = taskDefinition("only")),
                        ),
                        stopResult,
                    ),
            )
        val finalWorktree = VedWorktree.import(loadGitWorktree())
        // Default chunkSummarizer is HrsChunkSummarizer.unavailable — every request degrades to
        // null.
        val assistant = FakeHrsAssistant(result = HrsAssistant.Result(sampleReport, finalWorktree))

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                chunkConfig = chunkConfig,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)

        val finalLog = leader.invocations.last().delegationLog
        assertTrue(finalLog.smallChunkSummaries.isEmpty())
        assertTrue(finalLog.bigChunkSummaries.isEmpty())
      }

  @Test
  fun `once a chunk's summary is stored, its rendered chapter is byte-stable across later turns while only the tail grows`() =
      runBlocking {
        val chunkConfig = HrsChunkConfig(smallChunkSize = 3, bigChunkSize = 8)
        // 9 delegations = 3 small chunks: chunk 0 (t=0..2) closes on the 3rd, and — since the
        // full window only ever holds the *last two* small chunks — falls out of it and into the
        // small-summary tier as soon as a 3rd chunk starts (delegation count 7), well before that
        // 3rd chunk itself closes.
        val tasks = (1..9).map { n -> taskDefinition("task$n") }

        val leader =
            FakeHrsLeader(
                results =
                    tasks.map { task ->
                      delegateResult(HrsLeaderCommand.Delegate(taskDefinition = task))
                    } + stopResult,
            )

        val summary = HrsChunkSummary("zzstablesummaryzz")
        val chunkSummarizer =
            FakeHrsChunkSummarizer(summaryByKind = mapOf(HrsChunkSummaryKind.SmallChunk to summary))
        val finalWorktree = VedWorktree.import(loadGitWorktree())
        val assistant =
            FakeHrsAssistant(
                result =
                    HrsAssistant.Result(
                        report = sampleReport,
                        finalWorktree = finalWorktree,
                        chunkSummarizer = chunkSummarizer,
                    ),
            )

        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                leader = leader,
                assistant = assistant,
                chunkConfig = chunkConfig,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription = anyTaskDescription,
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)

        // 10 leader calls (sizes 0..9): one per delegation, plus the final Stop after the 9th.
        assertEquals(10, leader.invocations.size)

        val renderedBySize =
            leader.invocations.associate { context ->
              context.delegationLog.size to
                  context.delegationLog.renderLeaderHistory(chunkConfig).render()
            }

        // Chunk 0 (t=0..2) closes on the 3rd delegation, but the two-chunk full window still
        // renders it in full until a 3rd chunk starts (size 7) — only then does its stored summary
        // actually replace the full rendering. From size 7 onward, every later leader turn's
        // rendered history carries that exact stored summary text, byte-for-byte, even as more
        // delegations grow the tail behind it.
        for (size in 7..9) {
          assertTrue(
              renderedBySize.getValue(size).contains(summary.markdown),
              "size=$size should render the stored chunk-0 summary unchanged",
          )
        }

        // Stable because it was generated exactly once at close, not re-requested on every turn.
        assertEquals(
            1,
            chunkSummarizer.calls.count { (range, kind) ->
              range == 0..2 && kind == HrsChunkSummaryKind.SmallChunk
            },
        )
      }
}
