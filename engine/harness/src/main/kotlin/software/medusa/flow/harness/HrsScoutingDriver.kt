package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.flow.harness.HrsTaskCompleter.ScoutingObserver
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Drives the scouting conversation to completion: the frontline says what to open or expand, the
 * [HrsScoutDecisionInterpreter] extracts that as a structured adjustment, and the worktree grows
 * until the frontline declares readiness.
 *
 * Extracted from [HrsProperTaskCompleter] so the standalone `scout-fully` command can reuse it.
 */
object HrsScoutingDriver {
  private const val firstRoundNumber = 1

  data class ScoutingOutcome(
      val fullyScoutedWorktree: VedWorktree,
      val finalTimestamp: VedTimestamp,
  )

  suspend fun scoutFully(
      frontlineAiSystem: HrsFrontlineAiSystem,
      scoutDecisionInterpreter: HrsScoutDecisionInterpreter,
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      scoutingObserver: ScoutingObserver,
  ): ScoutingOutcome =
      continueScoutingRecursively(
          frontlineAiSystem = frontlineAiSystem,
          scoutDecisionInterpreter = scoutDecisionInterpreter,
          sourceGitWorktree = sourceGitWorktree,
          taskDescription = taskDescription,
          baseEditorWorktree = VedWorktree.import(sourceWorktree = sourceGitWorktree),
          baseScoutingLog = ScoutingLog.empty,
          startTimestamp = VedTimestamp.zero,
          roundNumber = firstRoundNumber,
          scoutingObserver = scoutingObserver,
      )

  private tailrec suspend fun continueScoutingRecursively(
      frontlineAiSystem: HrsFrontlineAiSystem,
      scoutDecisionInterpreter: HrsScoutDecisionInterpreter,
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      baseEditorWorktree: VedWorktree,
      baseScoutingLog: ScoutingLog,
      startTimestamp: VedTimestamp,
      roundNumber: Int,
      scoutingObserver: ScoutingObserver,
  ): ScoutingOutcome {
    val scoutMessage =
        frontlineAiSystem.performScouting(
            taskDescription = taskDescription,
            editorWorktree = baseEditorWorktree,
            scoutingLog = baseScoutingLog,
            scoutingObserver = scoutingObserver,
        )

    scoutingObserver.observeRound(
        roundNumber = roundNumber,
        baseEditorWorktree = baseEditorWorktree,
        scoutMessage = scoutMessage,
    )

    return when (
        val decision =
            scoutDecisionInterpreter.interpretDecision(
                scoutMessage = scoutMessage,
                editorWorktree = baseEditorWorktree,
            )
    ) {
      Decision.Stop ->
          ScoutingOutcome(
              fullyScoutedWorktree = baseEditorWorktree,
              finalTimestamp = startTimestamp,
          )

      is Decision.Continue -> {
        val adjustedWorktree =
            decision.requestedAdjustment
                .adjust(
                    gitWorktree = sourceGitWorktree,
                    editorWorktree = baseEditorWorktree,
                    timestamp = startTimestamp,
                )
                .adjustedWorktree

        continueScoutingRecursively(
            frontlineAiSystem = frontlineAiSystem,
            scoutDecisionInterpreter = scoutDecisionInterpreter,
            sourceGitWorktree = sourceGitWorktree,
            taskDescription = taskDescription,
            baseEditorWorktree = adjustedWorktree,
            baseScoutingLog =
                baseScoutingLog.expand(
                    newEntry =
                        ScoutingLog.LogEntry(
                            scoutMessage = scoutMessage,
                            systemResponse =
                                ScoutMessage.SystemResponse(
                                    performedAdjustment = decision.requestedAdjustment,
                                    adjustmentTimestamp = startTimestamp,
                                ),
                        ),
                ),
            startTimestamp = startTimestamp.next,
            roundNumber = roundNumber + 1,
            scoutingObserver = scoutingObserver,
        )
      }
    }
  }
}
