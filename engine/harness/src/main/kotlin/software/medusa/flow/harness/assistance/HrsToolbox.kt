package software.medusa.flow.harness.assistance

import kotlinx.serialization.json.JsonElement
import software.medusa.commons.openai_client.tools.OaiToolDefinition
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * The assistant's tool surface: the fixed [toolDefinitions] advertised to the model, and the single
 * [execute] entry point every tool call is dispatched through.
 *
 * A toolbox is stateless with respect to the worktree — [execute] takes the current [VedWorktree]
 * and returns the next one on success ([ToolOutcome.Applied]); the caller ([HrsProperAssistant])
 * threads that state across rounds. Anything the toolbox needs that does *not* change within a
 * delegation (git worktree access, the physical workspace it mirrors patches into, the project
 * connection `run_checks` runs against, the delegation's fixed timestamp) is construction-time
 * state on the concrete implementation, not part of this contract.
 */
interface HrsToolbox {
  /** The result of dispatching one tool call. */
  sealed class ToolOutcome {
    /**
     * The tool ran; [newWorktree] is the worktree to continue with, [resultText] the tool output.
     */
    data class Applied(
        val newWorktree: VedWorktree,
        val resultText: String,
    ) : ToolOutcome()

    /**
     * The call was malformed or violated a tool's preconditions (bad path, edit on a file that
     * doesn't exist, JSON that doesn't match the schema, ...). [guidanceText] is bounced back to
     * the model as the tool's output, unchanged.
     */
    data class Rejected(
        val guidanceText: String,
    ) : ToolOutcome()

    /** The `done` tool was called successfully; [report] ends the delegation thread. */
    data class Finished(
        val report: HrsDelegationReport,
    ) : ToolOutcome()
  }

  /** The tool definitions to configure the model's client with — fixed, independent of state. */
  val toolDefinitions: List<OaiToolDefinition>

  /**
   * Dispatches a single tool call by [toolName] against [worktree], decoding [rawArguments] against
   * that tool's schema. Never throws for a malformed call — see [ToolOutcome.Rejected].
   */
  suspend fun execute(
      toolName: String,
      rawArguments: JsonElement,
      worktree: VedWorktree,
  ): ToolOutcome
}
