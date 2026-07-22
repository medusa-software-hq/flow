package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiReasoningEffort
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.openai_client.messages.OaiUserName
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem.ImplementationPlan

class HrsProperExpertAiSystem(
    private val openaiClient: OaiConfiguredClient,
) : HrsExpertAiSystem {
  companion object {
    private const val simpleAiName = "ai"

    /**
     * Cap on the expert plan's output tokens. Without it the request sends no `max_tokens`, so
     * OpenRouter pre-authorizes the model's *full* max output (~65536 for the expert model) against
     * the key's remaining budget — on a pricey model that reservation is ~$1, which alone can
     * exceed a small weekly key limit and reject the call even though actual usage is cents (only
     * the tokens really produced are charged). This bounds the reservation to ~half while staying
     * generous for High-effort reasoning plus a plan that may include ready-to-use code.
     */
    private const val planMaxOutputTokenCount = 32768

    private val introText =
        """
        You are an expert software engineer. You're conversing with a lower-tier AI.
        """
            .trimIndent()

    private val userOutroText = "What should I do to complete The Task?"

    private val systemOutroText =
        """
        You are responsible for completing The Task.

        If possible, solve the problem yourself and present ready-to-use code.

        If spelling out the full solution would involve generating unacceptably high volume of text, solve the crucial parts of the task yourself and provide guidance on how to solve the remaining part.

        Use direct tone and imperative mood.
        """
            .trimIndent()
  }

  override suspend fun planImplementation(
      taskDescription: HrsTaskDescription,
      workspaceBrief: HrsExpertAiSystem.WorkspaceBrief,
  ): ImplementationPlan {
    val chatHistory =
        OaiChatHistory(
            messages =
                listOf(
                    OaiSystemMessage(
                        content = introText,
                    ),
                    OaiUserMessage(
                        content =
                            MdDocument(
                                    rootChapter =
                                        MdChapter(
                                            title = MdInlineContent.of("Problem"),
                                            element = MdElement.Empty,
                                            subChapters =
                                                listOf(
                                                    // The whole task chapter (its own sub-sections
                                                    // intact), retitled to sit under "Problem".
                                                    taskDescription.body.replaceTitle(
                                                        MdInlineContent.of("The Task"),
                                                    ),
                                                    MdChapter.leaf(
                                                        title =
                                                            MdInlineContent.of("Workspace brief"),
                                                        element =
                                                            MdElement(
                                                                blocks =
                                                                    listOf(
                                                                        MdBlock.CodeBlock(
                                                                            code =
                                                                                workspaceBrief.body,
                                                                        ),
                                                                    ),
                                                            ),
                                                    ),
                                                ),
                                        ),
                                )
                                .render(),
                        name = OaiUserName(simpleAiName),
                    ),
                    OaiUserMessage(
                        content = userOutroText,
                        name = OaiUserName(simpleAiName),
                    ),
                    OaiSystemMessage(
                        content = systemOutroText,
                    ),
                ),
        )

    val inferenceParams =
        OaiInferenceParams(
            reasoningEffort = OaiReasoningEffort.High,
            maxOutputTokenCount = planMaxOutputTokenCount,
        )

    val responseText =
        openaiClient
            .completeChat(chatHistory = chatHistory, inferenceParams = inferenceParams)
            .extractAssistantText()

    val implementationPlan =
        ImplementationPlan(
            body = responseText,
        )

    return implementationPlan
  }
}
