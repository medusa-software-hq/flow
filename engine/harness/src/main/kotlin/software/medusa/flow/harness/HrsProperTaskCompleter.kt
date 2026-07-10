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
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

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

    /**
     * The frontline occasionally describes a patch [patchInterpreter] can't turn into a real edit
     * (e.g. editing a file that was never opened) — an [IllegalArgumentException] from
     * [HrsPatchInterpreter.interpretPatch] or [VedWorktreePatch.patchWorktree]. That's a malformed
     * response, not a genuine health failure, so it's retried separately from
     * [maxImplementationAttempts] — a blind re-ask, since there's no existing structured-feedback
     * shape for "your patch didn't even apply" (that shape assumes the patch *did* apply and a
     * health check ran afterward).
     */
    private const val maxPatchInterpretationRetries = 3
  }

  /**
   * The recursive attempt loop's own result, translated into a [TaskCompletionResult] once done.
   */
  private sealed class ImplementationOutcome {
    data object Healthy : ImplementationOutcome()

    data class AttemptsExhausted(
        val attemptsMade: Int,
        val lastHealthStatus: ProjectHealthStatus.Unhealthy,
    ) : ImplementationOutcome()
  }

  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult {
    observer.observePhase(phase = HrsPipelinePhase.WorkspacePreparing)

    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity

    val projectManifest = projectManifestLoader.load(projectDirectory = sourceRootDirectory)

    val physicalWorkspace =
        physicalWorkspaceAllocator.allocateWorkspace(
            templateDirectory = sourceRootDirectory,
        )

    val physicalRootDirectory = physicalWorkspace.rootDirectory

    val projectConnection = projectManifest.connect(physicalWorkspace = physicalWorkspace)

    observer.observePhase(phase = HrsPipelinePhase.HealthGate)

    checkHealthInitially(
            projectConnection = projectConnection,
        )
        ?.let { initialHealthCheckFailure ->
          return initialHealthCheckFailure
        }

    observer.observePhase(phase = HrsPipelinePhase.Scouting)

    val scoutingOutcome =
        HrsScoutingDriver.scoutFully(
            frontlineAiSystem = frontlineAiSystem,
            scoutDecisionInterpreter = scoutDecisionInterpreter,
            sourceGitWorktree = sourceGitWorktree,
            taskDescription = taskDescription,
            scoutingObserver = observer.observeScouting(),
        )

    val fullyScoutedWorktree = scoutingOutcome.fullyScoutedWorktree

    observer.observePhase(phase = HrsPipelinePhase.WorkspaceBriefing)

    val workspaceBrief =
        frontlineAiSystem.prepareWorkspaceBrief(
            taskDescription = taskDescription,
            editorWorktree = fullyScoutedWorktree,
            workspaceBriefingObserver = observer.observeWorkspaceBriefing(),
        )

    observer.observePhase(phase = HrsPipelinePhase.ImplementationPlanning)

    val implementationPlan =
        expertAiSystem.planImplementation(
            taskDescription = taskDescription,
            workspaceBrief = workspaceBrief,
        )

    observer.observeImplementationPlan(
        implementationPlan = implementationPlan,
    )

    val implementationOutcome =
        implementSolutionFully(
            taskDescription = taskDescription,
            fullyScoutedWorktree = fullyScoutedWorktree,
            implementationPlan = implementationPlan,
            startTimestamp = scoutingOutcome.finalTimestamp,
            physicalRootDirectory = physicalRootDirectory,
            projectConnection = projectConnection,
            observer = observer,
        )

    return when (implementationOutcome) {
      ImplementationOutcome.Healthy ->
          TaskCompletionResult.Success(
              temporaryWorkspace =
                  HrsPhysicalTemporaryWorkspace(
                      physicalWorkspace = physicalWorkspace,
                  ),
          )

      is ImplementationOutcome.AttemptsExhausted ->
          TaskCompletionResult.Failure.AttemptsExhausted(
              attemptsMade = implementationOutcome.attemptsMade,
              lastHealthStatus = implementationOutcome.lastHealthStatus,
          )
    }
  }

  /**
   * Drives [HrsFrontlineAiSystem.implementSolution] in a loop: the frontline describes edits,
   * [patchInterpreter] turns them into a real patch, the patch is written into the materialized
   * workspace, and the health checks run there. Failures are fed back into the next round until the
   * workspace is healthy or [maxImplementationAttempts] is spent.
   */
  private suspend fun implementSolutionFully(
      taskDescription: HrsTaskDescription,
      fullyScoutedWorktree: VedWorktree,
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      startTimestamp: VedTimestamp,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      observer: Observer,
  ): ImplementationOutcome =
      continueImplementingRecursively(
          taskDescription = taskDescription,
          baseEditorWorktree = fullyScoutedWorktree,
          implementationPlan = implementationPlan,
          baseSolutionImplementationLog = SolutionImplementationLog.empty,
          startTimestamp = startTimestamp,
          physicalRootDirectory = physicalRootDirectory,
          projectConnection = projectConnection,
          observer = observer,
          solutionImplementationObserver = observer.observeSolutionImplementation(),
      )

  private tailrec suspend fun continueImplementingRecursively(
      taskDescription: HrsTaskDescription,
      baseEditorWorktree: VedWorktree,
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      baseSolutionImplementationLog: SolutionImplementationLog,
      startTimestamp: VedTimestamp,
      physicalRootDirectory: UfsMutableDirectory,
      projectConnection: UnpProjectConnection,
      observer: Observer,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ): ImplementationOutcome {
    val attemptNumber = baseSolutionImplementationLog.logEntries.size + 1

    observer.observePhase(
        phase =
            HrsPipelinePhase.ImplementationAttempt(
                attemptNumber = attemptNumber,
                maxAttempts = maxImplementationAttempts,
            ),
    )

    val (patchMessage, solutionApplicationResult) =
        implementAndApplyPatchWithRetries(
            taskDescription = taskDescription,
            baseEditorWorktree = baseEditorWorktree,
            implementationPlan = implementationPlan,
            baseSolutionImplementationLog = baseSolutionImplementationLog,
            startTimestamp = startTimestamp,
            attemptNumber = attemptNumber,
            solutionImplementationObserver = solutionImplementationObserver,
        )

    physicalRootDirectory.applyMutation(
        mutation = solutionApplicationResult.rootDirectoryMutation,
    )

    observer.observePhase(
        phase =
            HrsPipelinePhase.HealthCheck(
                attemptNumber = attemptNumber,
                maxAttempts = maxImplementationAttempts,
            ),
    )

    val healthStatus = verifySolutionHealth(projectConnection = projectConnection)

    solutionImplementationObserver.observeHealthStatus(healthStatus = healthStatus)

    return when (healthStatus) {
      ProjectHealthStatus.Healthy -> ImplementationOutcome.Healthy

      is ProjectHealthStatus.Unhealthy -> {
        if (attemptNumber >= maxImplementationAttempts) {
          ImplementationOutcome.AttemptsExhausted(
              attemptsMade = attemptNumber,
              lastHealthStatus = healthStatus,
          )
        } else {
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
              observer = observer,
              solutionImplementationObserver = solutionImplementationObserver,
          )
        }
      }
    }
  }

  /**
   * Asks the frontline for a patch and turns it into a [VedWorktreePatch.PatchApplicationResult],
   * retrying (re-asking from scratch, same log) up to [maxPatchInterpretationRetries] times if
   * [patchInterpreter] or [VedWorktreePatch.patchWorktree] rejects the response as malformed.
   */
  private suspend fun implementAndApplyPatchWithRetries(
      taskDescription: HrsTaskDescription,
      baseEditorWorktree: VedWorktree,
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      baseSolutionImplementationLog: SolutionImplementationLog,
      startTimestamp: VedTimestamp,
      attemptNumber: Int,
      solutionImplementationObserver: HrsTaskCompleter.SolutionImplementationObserver,
  ): Pair<HrsFrontlineAiSystem.PatchMessage, VedWorktreePatch.PatchApplicationResult> {
    var lastInterpretationError: IllegalArgumentException? = null

    repeat(maxPatchInterpretationRetries) {
      val patchMessage =
          frontlineAiSystem.implementSolution(
              taskDescription = taskDescription,
              editorWorktree = baseEditorWorktree,
              implementationPlan = implementationPlan,
              solutionImplementationLog = baseSolutionImplementationLog,
              solutionImplementationObserver = solutionImplementationObserver,
          )

      solutionImplementationObserver.observeImplementation(
          attemptNumber = attemptNumber,
          patchMessage = patchMessage,
      )

      try {
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

        return patchMessage to solutionApplicationResult
      } catch (e: IllegalArgumentException) {
        lastInterpretationError = e
      }
    }

    throw IllegalStateException(
        "The model failed to produce an applicable patch after $maxPatchInterpretationRetries " +
            "attempts. Last error: ${lastInterpretationError?.message}",
        lastInterpretationError,
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
