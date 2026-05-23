package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.openai_client.OpenAiClient

class ProperAiCodeMasker(
    @Suppress("unused") private val openAiClient: OpenAiClient,
) : AiCodeMasker {
  override fun maskCodeForTaskCompletion(
      taskDescription: String,
  ): AiCodePatcher.CodeMasker =
      object : AiCodePatcher.CodeMasker {
        override fun prepareMask(
            codeFileContent: TechFileContent.Code,
        ): MaskedCodeFileContent.Mask = MaskedCodeFileContent.Mask(maskedLineRanges = emptySet())
      }

  override fun maskCodeForIssueFixing(
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis,
  ): AiCodePatcher.CodeMasker =
      object : AiCodePatcher.CodeMasker {
        override fun prepareMask(
            codeFileContent: TechFileContent.Code,
        ): MaskedCodeFileContent.Mask = MaskedCodeFileContent.Mask(maskedLineRanges = emptySet())
      }
}
