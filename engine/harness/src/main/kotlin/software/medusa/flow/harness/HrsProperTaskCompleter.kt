package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.mutation.applyMutation
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationLog
import software.medusa.flow.harness.ai_system.HrsPatchInterpreter
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class HrsProperTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val projectManifestLoader: UnpProjectManifestLoader,
    private val frontlineAiSystem: HrsFrontlineAiSystem,
    private val scoutDecisionInterpreter: HrsScoutDecisionInterpreter,
    private val patchInterpreter: HrsPatchInterpreter,
    private val expertAiSystem: HrsExpertAiSystem,
) : HrsTaskCompleter {
  private companion object {
    private const val maxImplementationAttempts = 5
  }

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity

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

    val scoutingOutcome =
        HrsScoutingDriver.scoutFully(
            frontlineAiSystem = frontlineAiSystem,
            scoutDecisionInterpreter = scoutDecisionInterpreter,
            sourceGitWorktree = sourceGitWorktree,
            taskDescription = taskDescription,
            scoutingObserver = observer.observeScouting(),
        )

    val fullyScoutedWorktree = scoutingOutcome.fullyScoutedWorktree

    val workspaceBrief =
        frontlineAiSystem.prepareWorkspaceBrief(
            taskDescription = taskDescription,
            editorWorktree = fullyScoutedWorktree,
            workspaceBriefingObserver = observer.observeWorkspaceBriefing(),
        )

    val implementationPlan =
        expertAiSystem.planImplementation(
            taskDescription = taskDescription,
            workspaceBrief = workspaceBrief,
        )

    observer.observeImplementationPlan(
        implementationPlan = implementationPlan,
    )

    implementSolutionFully(
        taskDescription = taskDescription,
        fullyScoutedWorktree = fullyScoutedWorktree,
        implementationPlan = implementationPlan,
        startTimestamp = scoutingOutcome.finalTimestamp,
        physicalRootDirectory = physicalRootDirectory,
        projectConnection = projectConnection,
        solutionImplementationObserver = observer.observeSolutionImplementation(),
    )

    return TaskCompletionResult.Success(
        temporaryWorkspace =
            HrsPhysicalTemporaryWorkspace(
                physicalWorkspace = physicalWorkspace,
            ),
    )
  }

  /**
   * Drives [HrsFrontlineAiSystem.implementSolution] in a loop: the frontline describes edits,
   * [patchInterpreter] turns them into a real patch, the patch is written into the materialized
   * workspace, and the health checks run there. Failures are fed back into the next round until the
   * workspace is healthy. Throws if it does not become healthy within [maxImplementationAttempts].
   */
  private suspend fun implementSolutionFully(
      taskDescription: HrsTaskDescription,
      fullyScoutedWorktree: VedWorktree,
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      startTimestamp: VedTimestamp,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ) =
      continueImplementingRecursively(
          taskDescription = taskDescription,
          baseEditorWorktree = fullyScoutedWorktree,
          implementationPlan = implementationPlan,
          baseSolutionImplementationLog = SolutionImplementationLog.empty,
          startTimestamp = startTimestamp,
          physicalRootDirectory = physicalRootDirectory,
          projectConnection = projectConnection,
          solutionImplementationObserver = solutionImplementationObserver,
      )

  private tailrec suspend fun continueImplementingRecursively(
      taskDescription: HrsTaskDescription,
      baseEditorWorktree: VedWorktree,
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      baseSolutionImplementationLog: SolutionImplementationLog,
      startTimestamp: VedTimestamp,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ) {
    val patchMessage =
        frontlineAiSystem.implementSolution(
            taskDescription = taskDescription,
            editorWorktree = baseEditorWorktree,
            implementationPlan = implementationPlan,
            solutionImplementationLog = baseSolutionImplementationLog,
            solutionImplementationObserver = solutionImplementationObserver,
        )

    solutionImplementationObserver.observeImplementation(patchMessage = patchMessage)

    val solutionPatch =
        patchInterpreter.interpretPatch(
            patchMessage = patchMessage,
            editorWorktree = baseEditorWorktree,
        )

    val solutionApplicationResult =
        solutionPatch.patchWorktree(
            worktree = baseEditorWorktree,
            timestamp = startTimestamp,
        )

    physicalRootDirectory.applyMutation(
        mutation = solutionApplicationResult.rootDirectoryMutation,
    )

    val healthStatus = verifySolutionHealth(projectConnection = projectConnection)

    solutionImplementationObserver.observeHealthStatus(healthStatus = healthStatus)

    return when (healthStatus) {
      ProjectHealthStatus.Healthy -> Unit

      is ProjectHealthStatus.Unhealthy -> {
        check(baseSolutionImplementationLog.logEntries.size + 1 < maxImplementationAttempts) {
          "The solution was still unhealthy after $maxImplementationAttempts attempts"
        }

        continueImplementingRecursively(
            taskDescription = taskDescription,
            baseEditorWorktree = solutionApplicationResult.patchedWorktree,
            implementationPlan = implementationPlan,
            baseSolutionImplementationLog =
                baseSolutionImplementationLog.expand(
                    newEntry =
                        SolutionImplementationLog.LogEntry(
                            patchMessage = patchMessage,
                            systemResponse =
                                HrsFrontlineAiSystem.PatchMessage.SystemResponse(
                                    patchTimestamp = startTimestamp,
                                    failureReport = healthStatus.failureReport,
                                ),
                        ),
                ),
            startTimestamp = startTimestamp.next,
            physicalRootDirectory = physicalRootDirectory,
            projectConnection = projectConnection,
            solutionImplementationObserver = solutionImplementationObserver,
        )
      }
    }
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
  ): ProjectHealthStatus {
    val analyzeResult = projectConnection.analyzeAll()

    if (analyzeResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Analysis,
                  failure = analyzeResult,
              ),
      )
    }

    val testResult = projectConnection.testAll()

    if (testResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Testing,
                  failure = testResult,
              ),
      )
    }

    return ProjectHealthStatus.Healthy
  }
}
