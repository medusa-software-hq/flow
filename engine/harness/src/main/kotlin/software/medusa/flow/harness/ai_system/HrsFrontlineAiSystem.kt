package software.medusa.flow.harness.ai_system

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.markdown.MdElement
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

interface HrsFrontlineAiSystem {
  /** Worktree adjustment request with natural language commentary. */
  data class ScoutRequest(
      /** Natural language commentary, explaining the requested adjustment. */
      val rationale: MdElement,
      /** The requested adjustment to the worktree. */
      val requestedAdjustment: VedWorktreeAdjustment,
  ) {
    data class SystemResponse(
        val approvalTimestamp: VedTimestamp,
    ) {
      companion object
    }

    companion object
  }

  data class ScoutingLog(
      val logEntries: List<LogEntry>,
  ) {
    data class LogEntry(
        val scoutRequest: ScoutRequest,
        val systemResponse: ScoutRequest.SystemResponse,
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
      )
    }

    private tailrec suspend fun HrsFrontlineAiSystem.continueScoutingRecursively(
        sourceGitWorktree: GitWorktree,
        taskDescription: HrsTaskDescription,
        baseEditorWorktree: VedWorktree,
        baseScoutingLog: ScoutingLog,
        startTimestamp: VedTimestamp,
    ): FullScoutingResult {
      val continuedScoutingResult =
          performScouting(
              taskDescription = taskDescription,
              editorWorktree = baseEditorWorktree,
              scoutingLog = baseScoutingLog,
              timestamp = startTimestamp,
          )

      return when (continuedScoutingResult) {
        ScoutingResult.Completed ->
            FullScoutingResult(
                fullyScoutedWorktree = baseEditorWorktree,
                finalTimestamp = startTimestamp,
            )

        is ScoutingResult.Continued -> {
          val nextCommentedAdjustmentRequest = continuedScoutingResult.scoutRequest

          val adjustedWorktree =
              nextCommentedAdjustmentRequest.requestedAdjustment
                  .adjust(
                      gitWorktree = sourceGitWorktree,
                      editorWorktree = baseEditorWorktree,
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
                              scoutRequest = nextCommentedAdjustmentRequest,
                              systemResponse =
                                  ScoutRequest.SystemResponse(approvalTimestamp = startTimestamp),
                          ),
                  ),
              startTimestamp = startTimestamp.next,
          )
        }
      }
    }
  }

  sealed class ScoutingResult {
    /** The scouting process is completed. All the relevant files are opened. */
    data object Completed : ScoutingResult()

    /** The scouting goes on. */
    data class Continued(
        val scoutRequest: ScoutRequest,
    ) : ScoutingResult()

    companion object
  }

  suspend fun performScouting(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
      scoutingLog: ScoutingLog,
      timestamp: VedTimestamp,
  ): ScoutingResult

  data class SolutionImplementationResult(
      val solutionPatch: VedWorktreePatch,
  ) {
    companion object
  }

  suspend fun implementSolution(
      taskDescription: HrsTaskDescription,
      editorWorktree: VedWorktree,
  ): SolutionImplementationResult
}
