package software.medusa.flow.harness.assistance

import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * The leader/assistant engine's cheap role: one delegation thread, driven by a plain agentic tool
 * loop over an [HrsToolbox] (see [HrsProperAssistant]). A thread always ends with a report — via
 * the `done` tool on success, or a synthesized one if it runs out of budget — never a thrown
 * exception; the caller closes the delegation log segment with `(taskDefinition, report)`.
 */
interface HrsAssistant {
  /**
   * A finished thread: the report to append to the delegation log, the worktree it left, and
   * [chunkSummarizer] — the same thread's continuation, for the executor to call into at chunk
   * close (story 07). Defaults to [HrsChunkSummarizer.unavailable] so callers that don't care about
   * compaction (most tests) can omit it.
   */
  data class Result(
      val report: HrsDelegationReport,
      val finalWorktree: VedWorktree,
      val chunkSummarizer: HrsChunkSummarizer = HrsChunkSummarizer.unavailable,
  )

  /**
   * Runs one delegation: [context] carries everything besides the current task (the overall task,
   * the full branch journal, the worktree this thread starts from); [taskDefinition] is this
   * delegation's own instruction; [toolbox] is this delegation's fixed tool surface (already bound
   * to the physical workspace and project connection it mutates).
   */
  suspend fun runDelegation(
      context: HrsAssistanceContext,
      taskDefinition: HrsTaskDefinition,
      toolbox: HrsToolbox,
  ): Result
}
