package software.medusa.flow.harness

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.ScoutingObserver
import software.medusa.flow.harness.HrsTaskCompleter.SolutionImplementationObserver
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskCompleter.WorkspaceBriefingObserver
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationLog
import software.medusa.flow.harness.ai_system.HrsPatchInterpreter
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * Covers story 10's deliverable: [HrsProperTaskCompleter] fires the expected [HrsPipelinePhase]
 * sequence (matching `summary/01-engine-pipeline.md`'s documented order) for a happy-path run and
 * for an initial-health-gate failure, and returns a structured
 * [TaskCompletionResult.Failure.AttemptsExhausted] instead of throwing when every implementation
 * attempt leaves the project unhealthy.
 */
class HrsProperTaskCompleter_tests {
  private companion object {
    private val rootModulePath: UfsLiteralAbsolutePath =
        checkNotNull(UfsAbsolutePath.parse("/").toLiteral())
  }

  /**
   * Records every callback in order, as a flat list of tagged strings — enough to assert sequence.
   */
  private class RecordingObserver : Observer {
    val events = mutableListOf<String>()

    override fun observeScouting(): ScoutingObserver =
        object : ScoutingObserver {
          override fun observeRound(
              roundNumber: Int,
              baseEditorWorktree: VedWorktree,
              scoutMessage: ScoutMessage,
          ) {
            events += "scoutingRound($roundNumber)"
          }

          override fun observeRawResponse(
              responseText: String,
          ) = Unit
        }

    override fun observeSolutionImplementation(): SolutionImplementationObserver =
        object : SolutionImplementationObserver {
          override fun observeImplementation(
              attemptNumber: Int,
              patchMessage: PatchMessage,
          ) {
            events += "implementation($attemptNumber)"
          }

          override fun observeHealthStatus(
              healthStatus: ProjectHealthStatus,
          ) {
            val label = if (healthStatus == ProjectHealthStatus.Healthy) "healthy" else "unhealthy"
            events += "healthStatus($label)"
          }

          override fun observeRawResponse(
              responseText: String,
          ) = Unit
        }

    override fun observeWorkspaceBriefing(): WorkspaceBriefingObserver =
        WorkspaceBriefingObserver.Noop

    override fun observeImplementationPlan(
        implementationPlan: HrsExpertAiSystem.ImplementationPlan,
    ) {
      events += "implementationPlan"
    }

    override fun observePhase(
        phase: HrsPipelinePhase,
    ) {
      events +=
          when (phase) {
            HrsPipelinePhase.WorkspacePreparing -> "phase(WorkspacePreparing)"
            HrsPipelinePhase.HealthGate -> "phase(HealthGate)"
            HrsPipelinePhase.Scouting -> "phase(Scouting)"
            HrsPipelinePhase.WorkspaceBriefing -> "phase(WorkspaceBriefing)"
            HrsPipelinePhase.ImplementationPlanning -> "phase(ImplementationPlanning)"
            is HrsPipelinePhase.ImplementationAttempt ->
                "phase(ImplementationAttempt(${phase.attemptNumber}/${phase.maxAttempts}))"
            is HrsPipelinePhase.HealthCheck -> "phase(HealthCheck(${phase.attemptNumber}))"
          }
    }
  }

  /** Always stops after one round — the happy-path pipeline order doesn't need more. */
  private object SingleRoundScoutDecisionInterpreter : HrsScoutDecisionInterpreter {
    override suspend fun interpretDecision(
        scoutMessage: ScoutMessage,
        editorWorktree: VedWorktree,
    ): Decision = Decision.Stop
  }

  /**
   * A patch that changes nothing — sufficient since the fake project has no toolchain files to
   * break.
   */
  private object NoOpPatchInterpreter : HrsPatchInterpreter {
    override suspend fun interpretPatch(
        patchMessage: PatchMessage,
        editorWorktree: VedWorktree,
    ): VedWorktreePatch =
        VedWorktreePatch(rootDirectoryPatch = VedDirectoryPatch(childPatchByName = emptyMap()))
  }

  /**
   * Rejects the first [failuresBeforeSuccess] patches, then succeeds like [NoOpPatchInterpreter].
   */
  private class FlakyPatchInterpreter(
      private val failuresBeforeSuccess: Int,
  ) : HrsPatchInterpreter {
    var callCount = 0
      private set

    override suspend fun interpretPatch(
        patchMessage: PatchMessage,
        editorWorktree: VedWorktree,
    ): VedWorktreePatch {
      callCount += 1
      if (callCount <= failuresBeforeSuccess) {
        throw IllegalArgumentException("simulated malformed patch (call $callCount)")
      }
      return VedWorktreePatch(rootDirectoryPatch = VedDirectoryPatch(childPatchByName = emptyMap()))
    }
  }

  private object FakeExpertAiSystem : HrsExpertAiSystem {
    override suspend fun planImplementation(
        taskDescription: HrsTaskDescription,
        workspaceBrief: HrsExpertAiSystem.WorkspaceBrief,
    ): HrsExpertAiSystem.ImplementationPlan = HrsExpertAiSystem.ImplementationPlan(body = "plan")
  }

  private object FakeFrontlineAiSystem : HrsFrontlineAiSystem {
    override suspend fun performScouting(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
        scoutingLog: ScoutingLog,
        scoutingObserver: HrsTaskCompleter.ScoutingObserver,
    ): ScoutMessage = ScoutMessage(body = "scouting")

    override suspend fun prepareWorkspaceBrief(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
        workspaceBriefingObserver: HrsTaskCompleter.WorkspaceBriefingObserver,
    ): HrsExpertAiSystem.WorkspaceBrief = HrsExpertAiSystem.WorkspaceBrief(body = "brief")

    override suspend fun implementSolution(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
        implementationPlan: HrsExpertAiSystem.ImplementationPlan,
        solutionImplementationLog: SolutionImplementationLog,
        solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
    ): PatchMessage = PatchMessage(body = "patch")
  }

  /** No modules — every lifecycle phase (bootstrap/analyze/test) trivially succeeds. */
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
   * after — used to reach the implementation loop healthily, then exhaust every attempt.
   */
  private object PassesGateThenAlwaysUnhealthyProjectManifestLoader : UnpProjectManifestLoader {
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

  /**
   * A single module modeling unformatted-but-otherwise-correct Kotlin: `analyze` (standing in for
   * `ktfmtCheck`) fails until `normalize` (standing in for `ktfmtFormat`) has run at least once,
   * then passes forever after.
   */
  private object FormattingOnlyDefectProjectManifestLoader : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest {
      var normalized = false

      return SingleModuleManifest.asProjectManifest(
          connection =
              object : UnpModuleConnection {
                override suspend fun bootstrap() = UnpModuleConnection.Result.Success

                override suspend fun analyze() =
                    if (normalized) {
                      UnpModuleConnection.Result.Success
                    } else {
                      UnpModuleConnection.Result.Failure(diagnosticOutput = "unformatted Kotlin")
                    }

                override suspend fun test() = UnpModuleConnection.Result.Success

                override suspend fun normalize(): UnpModuleConnection.Result {
                  normalized = true
                  return UnpModuleConnection.Result.Success
                }
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
   * A real, minimal single-commit git repo — [GitWorktree] can only be built via
   * [GitWorktree.load].
   */
  private suspend fun loadGitWorktree(): GitWorktree {
    val dir = createTempDirectory(prefix = "hrs-task-completer-test").toFile()
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

  private fun buildTaskCompleter(
      projectManifestLoader: UnpProjectManifestLoader,
      patchInterpreter: HrsPatchInterpreter = NoOpPatchInterpreter,
  ): HrsProperTaskCompleter =
      HrsProperTaskCompleter(
          physicalWorkspaceAllocator = FakePhwWorkspaceAllocator(),
          projectManifestLoader = projectManifestLoader,
          frontlineAiSystem = FakeFrontlineAiSystem,
          scoutDecisionInterpreter = SingleRoundScoutDecisionInterpreter,
          patchInterpreter = patchInterpreter,
          expertAiSystem = FakeExpertAiSystem,
      )

  @Test
  fun `a happy-path run fires the phases in pipeline order and succeeds`() = runBlocking {
    val taskCompleter = buildTaskCompleter(projectManifestLoader = EmptyProjectManifestLoader)
    val observer = RecordingObserver()

    val result =
        taskCompleter.completeTask(
            sourceGitWorktree = loadGitWorktree(),
            taskDescription =
                HrsTaskDescription(
                    body =
                        MdChapter.leaf(
                            title = MdInlineContent.of("Task"),
                            element = MdElement.Empty,
                        )
                ),
            observer = observer,
        )

    assertIs<TaskCompletionResult.Success>(result)

    assertEquals(
        listOf(
            "phase(WorkspacePreparing)",
            "phase(HealthGate)",
            "phase(Scouting)",
            "scoutingRound(1)",
            "phase(WorkspaceBriefing)",
            "phase(ImplementationPlanning)",
            "implementationPlan",
            "phase(ImplementationAttempt(1/5))",
            "implementation(1)",
            "phase(HealthCheck(1))",
            "healthStatus(healthy)",
        ),
        observer.events,
    )
  }

  @Test
  fun `a formatting-only defect is auto-fixed by normalize and never fails the gate`() =
      runBlocking {
        val taskCompleter =
            buildTaskCompleter(projectManifestLoader = FormattingOnlyDefectProjectManifestLoader)
        val observer = RecordingObserver()

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription =
                    HrsTaskDescription(
                        body =
                            MdChapter.leaf(
                                title = MdInlineContent.of("Task"),
                                element = MdElement.Empty,
                            )
                    ),
                observer = observer,
            )

        assertIs<TaskCompletionResult.Success>(result)

        // No unhealthy status ever observed: normalize ran before both the initial and post-patch
        // gate.
        assertEquals(
            emptyList(),
            observer.events.filter { it == "healthStatus(unhealthy)" },
        )
      }

  @Test
  fun `an initial health-gate failure stops before scouting and is reported as JointOperation`() =
      runBlocking {
        val taskCompleter =
            buildTaskCompleter(projectManifestLoader = AlwaysUnhealthyProjectManifestLoader)
        val observer = RecordingObserver()

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription =
                    HrsTaskDescription(
                        body =
                            MdChapter.leaf(
                                title = MdInlineContent.of("Task"),
                                element = MdElement.Empty,
                            )
                    ),
                observer = observer,
            )

        val failure = assertIs<TaskCompletionResult.Failure.JointOperation>(result)
        assertEquals(HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis, failure.phase)

        // Only the two phases before the gate fired — nothing from scouting onward.
        assertEquals(
            listOf(
                "phase(WorkspacePreparing)",
                "phase(HealthGate)",
            ),
            observer.events,
        )
      }

  @Test
  fun `exhausting every implementation attempt returns a structured failure, not a throw`() =
      runBlocking {
        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = PassesGateThenAlwaysUnhealthyProjectManifestLoader,
            )
        val observer = RecordingObserver()

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription =
                    HrsTaskDescription(
                        body =
                            MdChapter.leaf(
                                title = MdInlineContent.of("Task"),
                                element = MdElement.Empty,
                            )
                    ),
                observer = observer,
            )

        val failure = assertIs<TaskCompletionResult.Failure.AttemptsExhausted>(result)
        assertEquals(5, failure.attemptsMade)

        val attemptPhases = observer.events.filter { it.startsWith("phase(ImplementationAttempt") }
        assertEquals(
            (1..5).map { "phase(ImplementationAttempt($it/5))" },
            attemptPhases,
        )

        val healthResults = observer.events.filter { it.startsWith("healthStatus") }
        assertEquals(List(5) { "healthStatus(unhealthy)" }, healthResults)
      }

  @Test
  fun `a malformed patch is retried and succeeds once the interpreter stops rejecting it`() =
      runBlocking {
        val patchInterpreter = FlakyPatchInterpreter(failuresBeforeSuccess = 2)
        val taskCompleter =
            buildTaskCompleter(
                projectManifestLoader = EmptyProjectManifestLoader,
                patchInterpreter = patchInterpreter,
            )

        val result =
            taskCompleter.completeTask(
                sourceGitWorktree = loadGitWorktree(),
                taskDescription =
                    HrsTaskDescription(
                        body =
                            MdChapter.leaf(
                                title = MdInlineContent.of("Task"),
                                element = MdElement.Empty,
                            )
                    ),
                observer = Observer.Noop,
            )

        assertIs<TaskCompletionResult.Success>(result)
        // 2 failures + 1 success, all within the same implementation attempt.
        assertEquals(3, patchInterpreter.callCount)
      }

  @Test
  fun `a persistently malformed patch throws after exhausting its retry budget`() = runBlocking {
    val patchInterpreter = FlakyPatchInterpreter(failuresBeforeSuccess = Int.MAX_VALUE)
    val taskCompleter =
        buildTaskCompleter(
            projectManifestLoader = EmptyProjectManifestLoader,
            patchInterpreter = patchInterpreter,
        )

    assertFailsWith<IllegalStateException> {
      taskCompleter.completeTask(
          sourceGitWorktree = loadGitWorktree(),
          taskDescription =
              HrsTaskDescription(
                  body =
                      MdChapter.leaf(title = MdInlineContent.of("Task"), element = MdElement.Empty)
              ),
          observer = Observer.Noop,
      )
    }

    // The retry budget, not the (much larger) implementation-attempt budget.
    assertEquals(3, patchInterpreter.callCount)
  }
}
