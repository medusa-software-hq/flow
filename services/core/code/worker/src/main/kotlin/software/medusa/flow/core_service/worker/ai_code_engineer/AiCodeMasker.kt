package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.CodeMasker
import software.medusa.flow.core_service.worker.code_project.CodeTool

interface AiCodeMasker {
  fun maskCodeForTaskCompletion(
      taskDescription: String,
  ): CodeMasker

  fun maskCodeForIssueFixing(
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis,
  ): CodeMasker
}
