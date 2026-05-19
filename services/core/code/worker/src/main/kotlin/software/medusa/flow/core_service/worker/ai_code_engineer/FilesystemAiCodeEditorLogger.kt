package software.medusa.flow.core_service.worker.ai_code_engineer

import java.time.Clock
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.medusa.commons.filesystem.compat.FilesystemMutableCompatFsComboLogger
import software.medusa.commons.filesystem.compat.MutableCompatFsComboLogger
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.extractOrCreateDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class FilesystemAiCodeEditorLogger(
    private val logDirectory: MutableCompatFsDirectory,
    private val clock: Clock,
) : AiCodeEditorLogger {
  companion object {
    private val json = Json { prettyPrint = true }
    private val workingDirectoryLogDirName = UnixPath.Name.Literal("working-directory")
  }

  override suspend fun logAttemptToCompleteTask(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      taskDescription: String,
  ): MutableCompatFsComboLogger {
    val entryDirectory = createEntryDirectory(operationName = "attemptToCompleteTask")

    writeTextFile(
        directory = entryDirectory,
        fileName = "relevantFilePaths.json",
        content =
            json.encodeToString(
                RelativeFilePathSetLog.serializer(),
                RelativeFilePathSetLog(
                    filePaths = relevantFilePaths.map { it.toUnixRelativePathString() }.sorted(),
                ),
            ),
    )
    writeTextFile(
        directory = entryDirectory,
        fileName = "taskDescription.txt",
        content = taskDescription,
    )

    val workingDirectoryLogDirectory =
        entryDirectory.extractOrCreateDirectory(name = workingDirectoryLogDirName)

    return FilesystemMutableCompatFsComboLogger(
        logDirectory = workingDirectoryLogDirectory,
        clock = clock,
    )
  }

  override suspend fun logAttemptToFixIssues(
      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): MutableCompatFsComboLogger {
    val entryDirectory = createEntryDirectory(operationName = "attemptToFixIssues")

    writeTextFile(
        directory = entryDirectory,
        fileName = "originalRelevantFilePaths.json",
        content =
            json.encodeToString(
                RelativeFilePathSetLog.serializer(),
                RelativeFilePathSetLog(
                    filePaths =
                        originalRelevantFilePaths.map { it.toUnixRelativePathString() }.sorted(),
                ),
            ),
    )
    writeTextFile(
        directory = entryDirectory,
        fileName = "originalTaskDescription.txt",
        content = originalTaskDescription,
    )
    writeTextFile(
        directory = entryDirectory,
        fileName = "moduleDiagnosis.json",
        content =
            json.encodeToString(
                IncorrectDiagnosisLog.serializer(),
                IncorrectDiagnosisLog(
                    diagnosisByFilePath =
                        moduleDiagnosis.diagnosisByFilePath
                            .mapKeys { (filePath, _) -> filePath.toUnixRelativePathString() }
                            .mapValues { (_, fileDiagnosis) ->
                              FileDiagnosisLog(
                                  issues = fileDiagnosis.issues.map { issue -> issue.description },
                              )
                            },
                ),
            ),
    )

    val workingDirectoryLogDirectory =
        entryDirectory.extractOrCreateDirectory(name = workingDirectoryLogDirName)

    return FilesystemMutableCompatFsComboLogger(
        logDirectory = workingDirectoryLogDirectory,
        clock = clock,
    )
  }

  private suspend fun createEntryDirectory(
      operationName: String,
  ): MutableCompatFsDirectory {
    val directoryName = UnixPath.Name.Literal("${clock.millis()}-$operationName")

    return logDirectory.extractOrCreateDirectory(name = directoryName)
  }

  private suspend fun writeTextFile(
      directory: MutableCompatFsDirectory,
      fileName: String,
      content: String,
  ) {
    directory.createFile(
        name = UnixPath.Name.Literal(fileName),
        initialContent = content.encodeToByteString(),
    )
  }

  @Serializable
  private data class RelativeFilePathSetLog(
      val filePaths: List<String>,
  )

  @Serializable
  private data class IncorrectDiagnosisLog(
      val diagnosisByFilePath: Map<String, FileDiagnosisLog>,
  )

  @Serializable
  private data class FileDiagnosisLog(
      val issues: List<String>,
  )
}
