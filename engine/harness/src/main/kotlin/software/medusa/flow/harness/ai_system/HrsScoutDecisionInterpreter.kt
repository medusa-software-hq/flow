package software.medusa.flow.harness.ai_system

import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment

/**
 * Interprets the frontline's free-form [ScoutMessage] into a structured scouting [Decision].
 *
 * The frontline speaks in natural language and is not trusted to follow a strict format, so the
 * message body is only interpreted here — lazily, and only as far as the driver needs: whether to
 * keep scouting and, if so, which worktree adjustment to apply.
 */
interface HrsScoutDecisionInterpreter {
  sealed class Decision {
    /** Scouting is complete — all the relevant files are open. */
    data object Stop : Decision()

    /** Scouting goes on with the requested [requestedAdjustment] applied to the worktree. */
    data class Continue(
        val requestedAdjustment: VedWorktreeAdjustment,
    ) : Decision()
  }

  suspend fun interpretDecision(
      scoutMessage: ScoutMessage,
      editorWorktree: VedWorktree,
  ): Decision
}
