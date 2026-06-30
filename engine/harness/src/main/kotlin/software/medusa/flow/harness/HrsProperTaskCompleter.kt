package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.mutation.applyMutation
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.Companion.implementSolutionFully
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.Companion.scoutFully
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

class HrsProperTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val aiSystem: HrsFrontlineAiSystem,
    private val projectManifestLoader: UnpProjectManifestLoader,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilesystemEntity

    val projectManifest = projectManifestLoader.load(projectDirectory = sourceRootDirectory)

    val physicalWorkspace =
        physicalWorkspaceAllocator.allocateWorkspace(
            templateDirectory = sourceRootDirectory,
        )

    val physicalRootDirectory = physicalWorkspace.rootDirectory

    val projectConnection = projectManifest.connect(physicalWorkspace = physicalWorkspace)

    checkHealthInitially(
            projectConnection = projectConnection,
        )
        ?.let { initialHealthCheckFailure ->
          return initialHealthCheckFailure
        }

    val fullScoutingResult =
        aiSystem.scoutFully(
            sourceGitWorktree = sourceGitWorktree,
            taskDescription = taskDescription,
            scoutingObserver = observer.observeScouting(),
        )

    // Each round the model proposes a patch; we write it into the materialized workspace and run
    // the
    // health checks there. Failures are fed back into the next round, so the model keeps revising
    // until the workspace is healthy.
    aiSystem.implementSolutionFully(
        taskDescription = taskDescription,
        editorWorktree = fullScoutingResult.fullyScoutedWorktree,
        verifier =
            object : HrsFrontlineAiSystem.SolutionVerifier {
              override suspend fun verify(
                  solutionApplicationResult: VedWorktreePatch.PatchApplicationResult,
              ): HrsFrontlineAiSystem.ProjectHealthStatus {
                physicalRootDirectory.applyMutation(
                    mutation = solutionApplicationResult.rootDirectoryMutation,
                )

                return verifySolutionHealth(projectConnection = projectConnection)
              }
            },
        startTimestamp = fullScoutingResult.finalTimestamp,
        solutionImplementationObserver = observer.observeSolutionImplementation(),
    )

    return TaskCompletionResult.Success(
        temporaryWorkspace =
            HrsPhysicalTemporaryWorkspace(
                physicalWorkspace = physicalWorkspace,
            ),
    )
  }

  private suspend fun checkHealthInitially(
      projectConnection: UnpProjectConnection,
  ): TaskCompletionResult.Failure.JointOperation? {
    val bootstrapResult = projectConnection.bootstrapAll()

    if (bootstrapResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.ProjectBootstrapping,
          operationFailure = bootstrapResult,
      )
    }

    val initialAnalyzeResult = projectConnection.analyzeAll()

    if (initialAnalyzeResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.InitialProjectAnalysis,
          operationFailure = initialAnalyzeResult,
      )
    }

    val initialTestResult = projectConnection.testAll()

    if (initialTestResult is JointResult.Failure) {
      return TaskCompletionResult.Failure.JointOperation(
          phase = JointOperationPhase.InitialProjectTesting,
          operationFailure = initialTestResult,
      )
    }

    return null
  }

  private suspend fun verifySolutionHealth(
      projectConnection: UnpProjectConnection,
  ): HrsFrontlineAiSystem.ProjectHealthStatus {
    val analyzeResult = projectConnection.analyzeAll()

    if (analyzeResult is JointResult.Failure) {
      return HrsFrontlineAiSystem.ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Analysis,
                  failure = analyzeResult,
              ),
      )
    }

    val testResult = projectConnection.testAll()

    if (testResult is JointResult.Failure) {
      return HrsFrontlineAiSystem.ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Testing,
                  failure = testResult,
              ),
      )
    }

    return HrsFrontlineAiSystem.ProjectHealthStatus.Healthy
  }
}
