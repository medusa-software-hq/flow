package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.filesystem.compat.MutableCompatFsComboLogger
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

interface AiCodeEditorLogger {
  suspend fun logAttemptToCompleteTask(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      taskDescription: String,
  ): MutableCompatFsComboLogger

  suspend fun logAttemptToFixIssues(
      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): MutableCompatFsComboLogger
}
