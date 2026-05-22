package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodePatcher_inputStructure_utils.encodeToTcMessage
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodePatcher_responseStructure_utils.StructuredResponse
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole
import software.medusa.openai_client.createStructuredCompletion

private val patchingModel = OpenAiModel.GptMidi

class JsonAiCodePatcher(
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
                extraContextMessages +
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.System,
                            text =
                                ProperAiCodePatcher_inputStructure_utils.maskedCodeCatalogTcGrammar,
                        ),
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = maskedCodeCatalog.encodeToTcMessage().encodeToString(),
                        ),
                    ),
        )

    val completionResponse =
        openAiClient.createStructuredCompletion(
            request =
                OpenAiClient.CompletionRequest(
                    input = completionInput,
                    model = patchingModel,
                ),
            responseSerializer = StructuredResponse.serializer(),
        )

    val structuredResponse = completionResponse.responseObject

    return structuredResponse.toChangeSet()
  }
}
