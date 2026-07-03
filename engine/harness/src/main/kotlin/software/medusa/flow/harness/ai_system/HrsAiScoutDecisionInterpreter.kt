package software.medusa.flow.harness.ai_system

import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
import software.medusa.commons.openai_client.createStructuredCompletion
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderDirectoryTree

/**
 * An [HrsScoutDecisionInterpreter] backed by a structured-output model call. It hands the
 * frontline's free-form scout message to the model and asks it to distil it into a
 * [HrsRawScoutDecision], which is then turned into a [Decision]. The message is trusted for its
 * *content*, not its format — the structured schema does the extraction the frontline is too weak
 * to do reliably itself.
 */
class HrsAiScoutDecisionInterpreter(
    private val openaiClient: OaiConfiguredClient,
) : HrsScoutDecisionInterpreter {
  private companion object {
    private val systemPromptText =
        """
        You extract a structured scouting decision from a message written by a scouting assistant.

        The assistant has been exploring a worktree to find the files relevant to a task. Its message says whether it considers scouting finished, and — if not — which currently-closed files it wants to open and which collapsed directories it wants to expand. The current worktree tree is given for reference.

        Read the message and fill in the schema. Every path must be absolute, starting with `/` (the worktree root); if the assistant wrote a path without a leading `/`, add it, and resolve it against the worktree tree. If the assistant is satisfied that all relevant files are open, mark scouting as complete and leave the path lists empty.
        """
            .trimIndent()
  }

  override suspend fun interpretDecision(
      scoutMessage: ScoutMessage,
      editorWorktree: VedWorktree,
  ): Decision {
    val request =
        OaiConfiguredClient.CompletionRequest(
            input =
                OaiChat(
                    messages =
                        listOf(
                            OaiMessage(role = OaiRole.System, text = systemPromptText),
                            OaiMessage(
                                role = OaiRole.System,
                                text = editorWorktree.renderDirectoryTree().render(),
                            ),
                            OaiMessage(role = OaiRole.User, text = scoutMessage.body),
                        ),
                ),
            reasoningEffort = OaiConfiguredClient.ReasoningEffort.Low,
        )

    val response =
        openaiClient.createStructuredCompletion(
            request = request,
            responseSchemaName = "scout_decision",
            responseSerializer = HrsRawScoutDecision.serializer(),
        )

    return response.responseObject.toDecision()
  }
}
