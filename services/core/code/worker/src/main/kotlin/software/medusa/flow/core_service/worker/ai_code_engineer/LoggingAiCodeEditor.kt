package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.filesystem.compat.LoggingMutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class LoggingAiCodeEditor(
    private val baseAiCodeEditor: AiCodeEditor,
    private val logger: AiCodeEditorLogger,
) : AiCodeEditor {
  override suspend fun attemptToCompleteTask(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      taskDescription: String,
  ): AiCodeEditor.FileEditor {
    val workingDirectoryLogger =
        logger.logAttemptToCompleteTask(
            relevantFilePaths = relevantFilePaths,
            taskDescription = taskDescription,
        )

    val baseFileEditor =
        baseAiCodeEditor.attemptToCompleteTask(
            relevantFilePaths = relevantFilePaths,
            taskDescription = taskDescription,
        )

    return object : AiCodeEditor.FileEditor {
      override suspend fun editWithin(
          workingDirectory: MutableCompatFsDirectory,
      ) {
        val loggingWorkingDirectory =
            LoggingMutableCompatFsDirectory(
                baseDirectory = workingDirectory,
                logger = workingDirectoryLogger,
            )

        baseFileEditor.editWithin(
            workingDirectory = loggingWorkingDirectory,
        )
      }
    }
  }

  override suspend fun attemptToFixIssues(
      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): AiCodeEditor.FileEditor {
    val workingDirectoryLogger =
        logger.logAttemptToFixIssues(
            originalRelevantFilePaths = originalRelevantFilePaths,
            originalTaskDescription = originalTaskDescription,
            moduleDiagnosis = moduleDiagnosis,
        )

    val baseFileEditor =
        baseAiCodeEditor.attemptToFixIssues(
            originalRelevantFilePaths = originalRelevantFilePaths,
            originalTaskDescription = originalTaskDescription,
            moduleDiagnosis = moduleDiagnosis,
        )

    return object : AiCodeEditor.FileEditor {
      override suspend fun editWithin(
          workingDirectory: MutableCompatFsDirectory,
      ) {
        val loggingWorkingDirectory =
            LoggingMutableCompatFsDirectory(
                baseDirectory = workingDirectory,
                logger = workingDirectoryLogger,
            )

        baseFileEditor.editWithin(
            workingDirectory = loggingWorkingDirectory,
        )
      }
    }
  }
}
