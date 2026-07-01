package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem.ImplementationPlan

class HrsProperExpertAiSystem(
    private val openaiClient: OaiConfiguredClient,
) : HrsExpertAiSystem {
  companion object {
    private val introText =
        """
        You are an expert software architect. Given **The Task**, produce a compact **Implementation Plan** for a weaker coding AI model.
        """
            .trimIndent()

    private val outroText =
        """
        The weaker model is good at boilerplate and following explicit instructions, but bad at ambiguity, architecture, and tricky logic. Your job is to solve as much of the hard part as possible and compress that into a plan the weaker model can implement reliably.

        Focus on maximum **decision density per token**:
        - make important design decisions up front
        - define the actual system shape (modules, entities, relations, methods, data flow)
        - use analogy/compression for low-risk repeated code
        - spell out anything subtle, error-prone, or easy to misinterpret

        Prefer top-down structure: architecture, files/modules, core entities/contracts, critical logic, implementation order.

        Write the output as a Markdown document starting with heading `# Implementation Plan`.

        Write the entire plan in the **imperative mood**: direct, simple, instruction-like sentences.

        Be concise, specific, and implementation-oriented. Avoid vague placeholders and avoid pushing important design choices onto the weaker model.
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
                                                MdChapter.wrapper(
                                                    title = MdInlineContent.of("Context"),
                                                    subChapters =
                                                        listOf(
                                                            MdChapter.leaf(
                                                                title =
                                                                    MdInlineContent.of("The Task"),
                                                                element = taskDescription.body,
                                                            ),
                                                            workspaceBrief.body.replaceTitle(
                                                                newTitle =
                                                                    MdInlineContent.of(
                                                                        "Workspace brief"
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                        )
                                        .render(),
                            ),
                            OaiMessage(
                                role = OaiRole.System,
                                text = outroText,
                            ),
                        ),
                ),
        )

    val response = openaiClient.createUnstructuredCompletion(request = request)

    val implementationPlan =
        ImplementationPlan(
            body = MdDocument.parse(markdownSource = response.responseText),
        )

    return implementationPlan
  }
}
