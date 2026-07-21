package software.medusa.flow.harness.ai_system

import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiReasoningEffort
import software.medusa.commons.openai_client.OaiResponseFormat
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
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
  companion object {
    /**
     * The JSON response format the interpreter's [openaiClient] must be configured with — the 0.2.0
     * `openai-client` fixes the structured schema at configuration time (was per call). Built from
     * [HrsRawScoutDecision]'s serializer, matching the old `createStructuredCompletion` path.
     */
    val responseFormat: OaiResponseFormat =
        OaiResponseFormat.Json(
            name = "scout_decision",
            schema =
                SerializationClassJsonSchemaGenerator.Default.generateSchema(
                    target = HrsRawScoutDecision.serializer().descriptor,
                ),
        )

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
    val chatHistory =
        OaiChatHistory(
            messages =
                listOf(
                    OaiSystemMessage(content = systemPromptText),
                    OaiSystemMessage(content = editorWorktree.renderDirectoryTree().render()),
                    OaiUserMessage(content = scoutMessage.body),
                ),
        )

    val rawDecision =
        openaiClient
            .completeChat(
                chatHistory = chatHistory,
                inferenceParams = OaiInferenceParams(reasoningEffort = OaiReasoningEffort.Low),
            )
            .decodeStructured(
                deserializer = HrsRawScoutDecision.serializer(),
            )

    return rawDecision.toDecision()
  }
}
