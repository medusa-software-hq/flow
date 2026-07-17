package software.medusa.flow.harness.leadership

import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.history.HrsChunkConfig
import software.medusa.flow.harness.history.HrsDelegationLog
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Everything a leader turn is rendered from: the overall task, the delegation log (rendered as the
 * chunk-summarized history), the worktree (rendered as the leader board — exposed content + stubs),
 * and the compaction/budget knobs. This is a data holder; assembling it into the actual leader
 * prompt is the leader client's job (story 05).
 */
data class HrsLeaderContext(
    val mainTask: HrsTaskDescription,
    val delegationLog: HrsDelegationLog,
    val worktree: VedWorktree,
    val softBudgetTokens: Int,
    val chunkConfig: HrsChunkConfig = HrsChunkConfig.default,
)
