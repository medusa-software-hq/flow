package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet
import software.medusa.flow.core_service.worker.ai_code_engineer.CcMdAiCodePatcher_wire_utils.encodeToCcMarkdownString
import software.medusa.flow.core_service.worker.ai_code_engineer.CcMdAiCodePatcher_wire_utils.parseChangeSet
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

private val patchingModel = OpenAiModel.GptMidi

class CcMdAiCodePatcher(
    private val openAiClient: OpenAiClient,
) : AiCodePatcher {
  override fun patchToCompleteTask(
      taskDescription: String,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generateChanges(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): ChangeSet =
            generateChangesViaAi(
                extraContextMessages =
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.System,
                            text = "Generate a patch set that completes the described coding task.",
                        ),
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = taskDescription,
                        ),
                    ),
                maskedCodeCatalog = maskedCodeCatalog,
            )
      }

  override fun patchToFixIssues(
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generateChanges(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): ChangeSet =
            generateChangesViaAi(
                extraContextMessages =
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.System,
                            text = "Generate a patch set that fixes the diagnosed code issues.",
                        ),
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text =
                                ProperAiCodePatcher_responseStructure_utils.buildIssueFixingPrompt(
                                    originalTaskDescription = originalTaskDescription,
                                    moduleDiagnosis = moduleDiagnosis,
                                ),
                        ),
                    ),
                maskedCodeCatalog = maskedCodeCatalog,
            )
      }

  private suspend fun generateChangesViaAi(
      extraContextMessages: List<OpenAiMessage>,
      maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
  ): ChangeSet {
    val completionInput =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text =
                            """
                            Response format:

                            ${CcMdAiCodePatcher_wire_utils.responseStructureDescription}

                            Generate an example response to confirm you understood the format.
                            """
                                .trimIndent(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = CcMdAiCodePatcher_wire_utils.examplePatchSetMarkdownText,
                    ),
                ) +
                    extraContextMessages +
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = maskedCodeCatalog.encodeToCcMarkdownString(),
                        ),
                    ),
        )

    val completionText =
        openAiClient
            .createUnstructuredCompletion(
                request =
                    OpenAiClient.CompletionRequest(
                        input = completionInput,
                        model = patchingModel,
                    ),
            )
            .responseText

    return parseChangeSet(
        responseText = completionText,
        maskedCodeCatalog = maskedCodeCatalog,
    )
  }
}
