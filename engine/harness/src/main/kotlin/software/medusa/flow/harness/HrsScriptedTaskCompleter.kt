package software.medusa.flow.harness

import kotlinx.coroutines.delay
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult

/**
 * A deterministic [HrsTaskCompleter] whose outcome is fixed by [behavior] rather than produced by a
 * model. It exists for the failure flows a real engine cannot produce on cue — an empty diff,
 * exhausted attempts, a hung worker — which were, until now, the most manual part of every demo.
 *
 * **Shipped but guarded.** This lives in the released binary (so kill/crash tests get real
 * process-level realism), but it is only ever wired in behind the CLI's
 * `FLOW_ALLOW_SCRIPTED_ENGINE` gate. A production worker can never select it.
 */
class HrsScriptedTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val behavior: Behavior,
    private val hangDurationMillis: Long = defaultHangDurationMillis,
) : HrsTaskCompleter {
  enum class Behavior {
    /** Produce a real one-file change, so the worker pushes a branch and opens a PR. */
    Patch,

    /** Produce a workspace identical to the source, so the publisher reports no changes. */
    EmptyDiff,

    /** Report the solution still unhealthy after every attempt — a structured failure result. */
    AttemptsExhausted,

    /** Never return: the worker's heartbeats keep the session alive until it is killed. */
    Hang,
  }

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult {
    observer.observePhase(HrsPipelinePhase.WorkspacePreparing)

    return when (behavior) {
      Behavior.Hang -> {
        // The worker is expected to be killed while this delay is outstanding. Only if it isn't do
        // we return — as a failure, so an un-killed hang never masquerades as success.
        delay(hangDurationMillis)
        HrsTaskCompleter.TaskCompletionResult.Failure.AttemptsExhausted(
            attemptsMade = 0,
            lastHealthStatus = unhealthy("scripted hang elapsed without the worker being killed"),
        )
      }

      Behavior.AttemptsExhausted ->
          HrsTaskCompleter.TaskCompletionResult.Failure.AttemptsExhausted(
              attemptsMade = scriptedAttemptsMade,
              lastHealthStatus = unhealthy(scriptedDiagnostic),
          )

      Behavior.EmptyDiff,
      Behavior.Patch -> {
        val workspace =
            physicalWorkspaceAllocator.allocateWorkspace(
                templateDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity,
            )

        // Mirrors the real completers: a thrown exception (e.g. the file write below) or a
        // session-abort cancellation must close `workspace` here rather than leak it.
        workspace.closeUnlessSuccessful {
          if (behavior == Behavior.Patch) {
            // A new file is the simplest edit that yields a diff regardless of the fixture
            // project.
            workspace.rootDirectory.createFile(
                name = UfsName.Literal(scriptedChangeFileName),
                initialContent = "scripted change\n".encodeToByteString(),
            )
          }

          HrsTaskCompleter.TaskCompletionResult.Success(
              temporaryWorkspace = HrsPhysicalTemporaryWorkspace(physicalWorkspace = workspace),
          )
        }
      }
    }
  }

  private fun unhealthy(
      diagnostic: String,
  ): ProjectHealthStatus.Unhealthy =
      ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Testing,
                  failure =
                      JointResult.Failure(
                          failureByModulePath =
                              mapOf(
                                  UfsAbsolutePath.Root to
                                      UnpModuleConnection.Result.Failure(
                                          diagnosticOutput = diagnostic
                                      ),
                              ),
                      ),
              ),
      )

  companion object {
    const val defaultHangDurationMillis = 10 * 60 * 1000L

    const val scriptedChangeFileName = ".flow-scripted-change"
    const val scriptedAttemptsMade = 3
    const val scriptedDiagnostic = "scripted engine: the solution is deliberately unhealthy"
  }
}
