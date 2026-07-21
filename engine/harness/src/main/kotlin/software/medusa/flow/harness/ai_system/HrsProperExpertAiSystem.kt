package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiConfiguredClient.ReasoningEffort
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
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
    val request =
        OaiConfiguredClient.CompletionRequest(
            input =
                OaiChat(
                    messages =
                        listOf(
                            OaiMessage(
                                role = OaiRole.System,
                                text = introText,
                            ),
                            OaiMessage(
                                role = OaiRole.User,
                                text =
                                    MdDocument(
                                            rootChapter =
                                                MdChapter(
                                                    title = MdInlineContent.of("Problem"),
                                                    element = MdElement.Empty,
                                                    subChapters =
                                                        listOf(
                                                            MdChapter.leaf(
                                                                title =
                                                                    MdInlineContent.of("The Task"),
                                                                element = taskDescription.body,
                                                            ),
                                                            MdChapter.leaf(
                                                                title =
                                                                    MdInlineContent.of(
                                                                        "Workspace brief"
                                                                    ),
                                                                element =
                                                                    MdElement(
                                                                        blocks =
                                                                            listOf(
                                                                                MdBlock.CodeBlock(
                                                                                    code =
                                                                                        workspaceBrief
                                                                                            .body,
                                                                                ),
                                                                            ),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                        )
                                        .render(),
                                name = simpleAiName,
                            ),
                            OaiMessage(
                                role = OaiRole.User,
                                text = userOutroText,
                                name = simpleAiName,
                            ),
                            OaiMessage(
                                role = OaiRole.System,
                                text = systemOutroText,
                            ),
                        ),
                ),
            reasoningEffort = ReasoningEffort.High,
            maxOutputTokenCount = planMaxOutputTokenCount,
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    val implementationPlan =
        ImplementationPlan(
            body = response.responseText,
        )

    return implementationPlan
  }
}
