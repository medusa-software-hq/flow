package software.medusa.flow.harness.ai_system

import software.medusa.flow.harness.HrsTaskCompleter.ScoutingObserver
import software.medusa.flow.harness.HrsTaskCompleter.SolutionImplementationObserver
import software.medusa.flow.harness.HrsTaskCompleter.WorkspaceBriefingObserver
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem.ImplementationPlan
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

/**
 * The conversational, high-context (but not very smart) system that talks directly to the worktree.
 *
 * Each phase yields a free-form natural-language message ([ScoutMessage], [PatchMessage]); the
 * value class carries the assumption about what the message is *about*, but the body is left
 * unparsed. It is up to the driver (via [HrsScoutDecisionInterpreter] / [HrsPatchInterpreter]) to
 * extract structure from a message, and only when it actually needs to act on it.
 */
interface HrsFrontlineAiSystem {
  data class ScoutingLog(
      val logEntries: List<LogEntry>,
  ) {
    data class LogEntry(
        val scoutMessage: ScoutMessage,
        val systemResponse: ScoutMessage.SystemResponse,
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

  @JvmInline
  value class ScoutMessage(
      val body: String,
  ) {
    data class SystemResponse(
        val performedAdjustment: VedWorktreeAdjustment,
        val adjustmentTimestamp: VedTimestamp,
    ) {
      companion object
    }
  }

  suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      scoutingObserver: ScoutingObserver,
  ): ScoutMessage

  @JvmInline
  value class PatchMessage(
      val body: String,
  ) {
    data class SystemResponse(
        val patchTimestamp: VedTimestamp,
        val failureReport: ProjectFailureReport,
    )
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

  /** The history of solution attempts, each paired with the issues it left behind. */
  data class SolutionImplementationLog(
      val logEntries: List<LogEntry>,
  ) {
    data class LogEntry(
        val patchMessage: PatchMessage,
        val systemResponse: PatchMessage.SystemResponse,
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
  ): PatchMessage

  suspend fun prepareWorkspaceBrief(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      workspaceBriefingObserver: WorkspaceBriefingObserver,
  ): HrsExpertAiSystem.WorkspaceBrief
}
