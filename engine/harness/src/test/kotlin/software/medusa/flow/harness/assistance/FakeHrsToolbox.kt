package software.medusa.flow.harness.assistance

import kotlinx.serialization.json.JsonElement
import software.medusa.commons.openai_client.tools.OaiToolDefinition
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * A scriptable [HrsToolbox]: every call is recorded in [calls] and dispatched to caller-supplied
 * [behaviorByToolName], falling back to [defaultBehavior] for anything not listed there. Lets a
 * driver test (e.g. the future story wiring [HrsAssistant] into a real delegation loop) script tool
 * behaviour without standing up a real worktree/physical workspace/project connection.
 */
class FakeHrsToolbox(
    override val toolDefinitions: List<OaiToolDefinition> = emptyList(),
    private val defaultBehavior: (String, JsonElement, VedWorktree) -> HrsToolbox.ToolOutcome =
        { toolName, _, _ ->
          HrsToolbox.ToolOutcome.Rejected(
              guidanceText = "No fake behavior scripted for `$toolName`."
          )
        },
    private val behaviorByToolName:
        Map<String, (JsonElement, VedWorktree) -> HrsToolbox.ToolOutcome> =
        emptyMap(),
) : HrsToolbox {
  data class Call(
      val toolName: String,
      val rawArguments: JsonElement,
      val worktree: VedWorktree,
  )

  val calls: MutableList<Call> = mutableListOf()

  override suspend fun execute(
      toolName: String,
      rawArguments: JsonElement,
      worktree: VedWorktree,
  ): HrsToolbox.ToolOutcome {
    calls += Call(toolName = toolName, rawArguments = rawArguments, worktree = worktree)

    val behavior = behaviorByToolName[toolName]

    return behavior?.invoke(rawArguments, worktree)
        ?: defaultBehavior(toolName, rawArguments, worktree)
  }
}
