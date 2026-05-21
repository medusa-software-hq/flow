package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.serialization.ccon.CconElement
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.PatchSet
import software.medusa.flow.core_service.worker.ai_code_engineer.CconAiCodePatcher_wire_utils.encodeToCconString
import software.medusa.flow.core_service.worker.ai_code_engineer.CconAiCodePatcher_wire_utils.parsePatchSet
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

private val patchingModel = OpenAiModel.DeepSeekPro

class CconAiCodePatcher(
    private val openAiClient: OpenAiClient,
) : AiCodePatcher {
  override fun patchToCompleteTask(
      taskDescription: String,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generatePatches(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): PatchSet =
            generatePatchesViaAi(
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
        override suspend fun generatePatches(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): PatchSet =
            generatePatchesViaAi(
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

  private suspend fun generatePatchesViaAi(
      extraContextMessages: List<OpenAiMessage>,
      maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
  ): PatchSet {
    val completionInput =
        OpenAiChat(
            messages =
                listOf(
                    /*

                           Response grammar:

                           ${CconAiCodePatcher_wire_utils.ebnfResponseGrammarDescription}
                    */
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text =
                            """
                            CCON grammar:

                            ${CconElement.ebnfGrammarDescription}

                            Response CCON schema:

                            ${CconAiCodePatcher_wire_utils.responseSchemaDescription}

                            Generate an example response to confirm you understood the schema.
                            """
                                .trimIndent(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = CconAiCodePatcher_wire_utils.examplePatchSetCconText,
                    ),
                ) +
                    extraContextMessages +
                    listOf(
                        OpenAiMessage(
                            role = OpenAiRole.User,
                            text = maskedCodeCatalog.encodeToCconString(),
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

    return CconAiCodePatcher_wire_utils.parsePatchSet(
        responseText = completionText,
        maskedCodeCatalog = maskedCodeCatalog,
    )
  }
}
