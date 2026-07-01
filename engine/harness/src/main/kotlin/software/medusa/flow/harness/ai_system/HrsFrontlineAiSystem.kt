package software.medusa.flow.harness.ai_system

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.markdown.MdElement
import software.medusa.flow.harness.HrsTaskCompleter.ScoutingObserver
import software.medusa.flow.harness.HrsTaskCompleter.SolutionImplementationObserver
import software.medusa.flow.harness.HrsTaskCompleter.WorkspaceBriefingObserver
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem.ImplementationPlan
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.Companion.maxImplementationAttempts
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

interface HrsFrontlineAiSystem {
  data class ScoutingLog(
      val logEntries: List<LogEntry>,
  ) {
    data class LogEntry(
        val continueCommand: ScoutCommand.Continue,
        val systemResponse: ScoutCommand.SystemResponse,
    )

    companion object {
      val empty =
          ScoutingLog(
              logEntries = emptyList(),
          )
    }

    fun expand(
        newEntry: LogEntry,
    ): ScoutingLog =
        ScoutingLog(
            logEntries = logEntries + newEntry,
        )
  }

  companion object {
    data class FullScoutingResult(
        val fullyScoutedWorktree: VedWorktree,
        val finalTimestamp: VedTimestamp,
    )

    suspend fun HrsFrontlineAiSystem.scoutFully(
        sourceGitWorktree: GitWorktree,
        taskDescription: HrsTaskDescription,
        scoutingObserver: ScoutingObserver,
    ): FullScoutingResult {
      val stubEditorWorktree =
          VedWorktree.import(
              sourceWorktree = sourceGitWorktree,
          )

      val initialTimestamp = VedTimestamp.zero

      return continueScoutingRecursively(
          sourceGitWorktree = sourceGitWorktree,
          taskDescription = taskDescription,
          baseEditorWorktree = stubEditorWorktree,
          baseScoutingLog = ScoutingLog.empty,
          startTimestamp = initialTimestamp,
          scoutingObserver = scoutingObserver,
      )
    }

    private tailrec suspend fun HrsFrontlineAiSystem.continueScoutingRecursively(
        sourceGitWorktree: GitWorktree,
        taskDescription: HrsTaskDescription,
        baseEditorWorktree: VedWorktree,
        baseScoutingLog: ScoutingLog,
        startTimestamp: VedTimestamp,
        scoutingObserver: ScoutingObserver,
    ): FullScoutingResult {
      val scoutingResult =
          performScouting(
              taskDescription = taskDescription,
              editorWorktree = baseEditorWorktree,
              scoutingLog = baseScoutingLog,
              scoutingObserver = scoutingObserver,
          )

      scoutingObserver.observeRound(
          baseEditorWorktree = baseEditorWorktree,
          scoutCommand = scoutingResult,
      )

      return when (scoutingResult) {
        ScoutCommand.Stop ->
            FullScoutingResult(
                fullyScoutedWorktree = baseEditorWorktree,
                finalTimestamp = startTimestamp,
            )

        is ScoutCommand.Continue -> {
          val adjustedWorktree =
              scoutingResult.requestedAdjustment
                  .adjust(
                      gitWorktree = sourceGitWorktree,
                      editorWorktree = baseEditorWorktree,
                      timestamp = startTimestamp,
                  )
                  .adjustedWorktree

          continueScoutingRecursively(
              sourceGitWorktree = sourceGitWorktree,
              taskDescription = taskDescription,
              baseEditorWorktree = adjustedWorktree,
              baseScoutingLog =
                  baseScoutingLog.expand(
                      newEntry =
                          ScoutingLog.LogEntry(
                              continueCommand = scoutingResult,
                              systemResponse =
                                  ScoutCommand.SystemResponse(
                                      approvalTimestamp = startTimestamp,
                                  ),
                          ),
                  ),
              startTimestamp = startTimestamp.next,
              scoutingObserver = scoutingObserver,
          )
        }
      }
    }

    data class FullSolutionImplementationResult(
        val finalWorktree: VedWorktree,
        val finalTimestamp: VedTimestamp,
    )

    private const val maxImplementationAttempts = 5

    /**
     * Drives [implementSolution] in a loop: the model proposes a patch, [verifier] applies it and
     * runs the project's health checks, and any issues are fed back into the next round until the
     * worktree is healthy. Throws if it does not become healthy within [maxImplementationAttempts].
     */
    suspend fun HrsFrontlineAiSystem.implementSolutionFully(
        taskDescription: HrsTaskDescription,
        editorWorktree: VedWorktree,
        implementationPlan: ImplementationPlan,
        verifier: SolutionVerifier,
        startTimestamp: VedTimestamp,
        solutionImplementationObserver: SolutionImplementationObserver,
    ): FullSolutionImplementationResult =
        continueImplementingRecursively(
            taskDescription = taskDescription,
            baseEditorWorktree = editorWorktree,
            implementationPlan = implementationPlan,
            baseSolutionImplementationLog = SolutionImplementationLog.empty,
            startTimestamp = startTimestamp,
            verifier = verifier,
            solutionImplementationObserver = solutionImplementationObserver,
        )

    private tailrec suspend fun HrsFrontlineAiSystem.continueImplementingRecursively(
        taskDescription: HrsTaskDescription,
        baseEditorWorktree: VedWorktree,
        implementationPlan: ImplementationPlan,
        baseSolutionImplementationLog: SolutionImplementationLog,
        startTimestamp: VedTimestamp,
        verifier: SolutionVerifier,
        solutionImplementationObserver: SolutionImplementationObserver,
    ): FullSolutionImplementationResult {
      val solutionImplementationResult =
          implementSolution(
              taskDescription = taskDescription,
              editorWorktree = baseEditorWorktree,
              implementationPlan = implementationPlan,
              solutionImplementationLog = baseSolutionImplementationLog,
              solutionImplementationObserver = solutionImplementationObserver,
          )

      solutionImplementationObserver.observeImplementation(
          patchCommand = solutionImplementationResult,
      )

      val solutionApplicationResult =
          solutionImplementationResult.solutionPatch.patchWorktree(
              worktree = baseEditorWorktree,
              timestamp = startTimestamp,
          )

      val healthStatus = verifier.verify(solutionApplicationResult = solutionApplicationResult)

      solutionImplementationObserver.observeHealthStatus(healthStatus = healthStatus)

      return when (healthStatus) {
        ProjectHealthStatus.Healthy ->
            FullSolutionImplementationResult(
                finalWorktree = solutionApplicationResult.patchedWorktree,
                finalTimestamp = startTimestamp,
            )

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
                              patchCommand = solutionImplementationResult,
                              systemResponse =
                                  PatchCommand.SystemResponse(
                                      approvalTimestamp = startTimestamp,
                                      failureReport = healthStatus.failureReport,
                                  ),
                          ),
                  ),
              startTimestamp = startTimestamp.next,
              verifier = verifier,
              solutionImplementationObserver = solutionImplementationObserver,
          )
        }
      }
    }
  }

  sealed class ScoutCommand {
    /** The scouting process is completed. All the relevant files are opened. */
    data object Stop : ScoutCommand()

    /** The scouting goes on. */
    data class Continue(
        /** Natural language commentary, explaining the requested adjustment. */
        val rationale: MdElement,
        /** The requested adjustment to the worktree. */
        val requestedAdjustment: VedWorktreeAdjustment,
    ) : ScoutCommand() {
      companion object
    }

    data class SystemResponse(
        val approvalTimestamp: VedTimestamp,
    ) {
      companion object
    }

    companion object
  }

  suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      scoutingObserver: ScoutingObserver,
  ): ScoutCommand

  data class PatchCommand(
      val solutionPatch: VedWorktreePatch,
  ) {
    data class SystemResponse(
        val approvalTimestamp: VedTimestamp,
        val failureReport: ProjectFailureReport,
    )

    companion object
  }

  data class ProjectFailureReport(
      val stage: Stage,
      val failure: JointResult.Failure,
  ) {
    enum class Stage {
      Analysis,
      Testing,
    }
  }

  /** Verdict on a worktree after a solution patch has been applied and health-checked. */
  sealed class ProjectHealthStatus {
    /** Every health check passed. */
    data object Healthy : ProjectHealthStatus()

    /**
     * At least one health check failed; [failureReport] describes the issues for the model to fix.
     */
    data class Unhealthy(
        val failureReport: ProjectFailureReport,
    ) : ProjectHealthStatus()
  }

  /** Applies a candidate solution patch to the real workspace and reports its health. */
  interface SolutionVerifier {
    suspend fun verify(
        solutionApplicationResult: VedWorktreePatch.PatchApplicationResult,
    ): ProjectHealthStatus
  }

  /** The history of solution attempts, each paired with the issues it left behind. */
  data class SolutionImplementationLog(
      val logEntries: List<LogEntry>,
  ) {
    data class LogEntry(
        val patchCommand: PatchCommand,
        val systemResponse: PatchCommand.SystemResponse,
    )

    companion object {
      val empty = SolutionImplementationLog(logEntries = emptyList())
    }

    fun expand(
        newEntry: LogEntry,
    ): SolutionImplementationLog = SolutionImplementationLog(logEntries = logEntries + newEntry)
  }

  suspend fun implementSolution(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      implementationPlan: ImplementationPlan,
      solutionImplementationLog: SolutionImplementationLog,
      solutionImplementationObserver: SolutionImplementationObserver,
  ): PatchCommand

  suspend fun prepareWorkspaceBrief(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      workspaceBriefingObserver: WorkspaceBriefingObserver,
  ): HrsExpertAiSystem.WorkspaceBrief
}
