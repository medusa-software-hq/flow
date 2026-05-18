package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class ProperAiCodeEditor(
    private val aiCodePatcher: AiCodePatcher,
    private val aiCodeMasker: AiCodeMasker,
) : AiCodeEditor {
  override suspend fun attemptToCompleteTask(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      taskDescription: String,
  ): AiCodeEditor.FileEditor =
      aiCodePatcher
          .patchToCompleteTask(
              taskDescription = taskDescription,
          )
          .masking(
              masker =
                  aiCodeMasker.maskCodeForTaskCompletion(
                      taskDescription = taskDescription,
                  ),
          )
          .selecting(
              selector =
                  AiCodeEditor.FileSelector.static(
                      filePaths = relevantFilePaths,
                  ),
          )

  override suspend fun attemptToFixIssues(
      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): AiCodeEditor.FileEditor =
      aiCodePatcher
          .patchToFixIssues(
              originalTaskDescription = originalTaskDescription,
              moduleDiagnosis = moduleDiagnosis,
          )
          .masking(
              masker =
                  aiCodeMasker.maskCodeForIssueFixing(
                      originalTaskDescription = originalTaskDescription,
                      moduleDiagnosis = moduleDiagnosis,
                  ),
          )
          .selecting(
              selector =
                  AiCodeEditor.FileSelector.static(
                      filePaths = originalRelevantFilePaths + moduleDiagnosis.filePathsWithIssues,
                  ),
          )
}
