package software.medusa.flow.harness.assistance

import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.history.HrsDelegationLog
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Everything an assistant thread reads besides its current task definition (passed separately, per
 * the assistant contract): the overall task, the full branch journal (rendered from the delegation
 * log zipped with the worktree's version history), and the live worktree it navigates. A data
 * holder; the assistant client assembles the thread prompt (later stories).
 */
data class HrsAssistanceContext(
    val mainTask: HrsTaskDescription,
    val delegationLog: HrsDelegationLog,
    val worktree: VedWorktree,
)
