package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.extractDeepMutable
import software.medusa.commons.filesystem.compat.extractDeepReadonly
import software.medusa.commons.filesystem.compat.readText
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.ChangeApplier
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.CodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.FileEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.PatchGenerator
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

interface AiCodeEditor {
  interface FileSelector {
    companion object {
      fun static(
          filePaths: Set<LiteralRelativeUnixPath>,
      ): FileSelector =
          object : FileSelector {
            override suspend fun pickFiles(
                sourceDirectory: ReadonlyCompatFsDirectory,
            ): CodeCatalog =
                CodeCatalog(
                    codeFileContentByPath =
                        filePaths.associateWith { filePath ->
                          val fileEntity =
                              sourceDirectory.extractDeepReadonly(filePath)
                                  ?: throw IllegalStateException(
                                      "Expected file at path ${filePath.toUnixRelativePathString()}",
                                  )

                          val file =
                              fileEntity as? ReadonlyCompatFsFile
                                  ?: throw IllegalStateException(
                                      "Expected file at path ${filePath.toUnixRelativePathString()}, but found a directory",
                                  )

                          CodeFileContent.parse(
                              rawContent = file.readText(),
                          )
                        },
                )
          }
    }

    suspend fun pickFiles(
        sourceDirectory: ReadonlyCompatFsDirectory,
    ): CodeCatalog
  }

  data class CodeCatalog(
      val codeFileContentByPath: Map<LiteralRelativeUnixPath, CodeFileContent>,
  ) {
    suspend fun writeBack(
        targetDirectory: MutableCompatFsDirectory,
    ) {
      codeFileContentByPath.forEach { (filePath, fileContent) ->
        targetDirectory.writeCodeFile(
            filePath = filePath,
            fileContent = fileContent,
        )
      }
    }

    fun applyChangeSet(
        changeSet: AiCodePatcher.ChangeSet,
    ): CodeCatalog =
        CodeCatalog(
            codeFileContentByPath =
                buildMap {
                  val handledPaths = mutableSetOf<LiteralRelativeUnixPath>()

                  for ((filePath, fileContent) in codeFileContentByPath) {
                    handledPaths += filePath

                    val change =
                        changeSet.changeByFilePath[filePath]
                            ?: run {
                              put(filePath, fileContent)
                              continue
                            }

                    when (change) {
                      is AiCodePatcher.ChangeSet.Change.Patch ->
                          put(filePath, fileContent.applyChange(change))
                      is AiCodePatcher.ChangeSet.Change.Create ->
                          put(filePath, CodeFileContent(code = change.content))
                      AiCodePatcher.ChangeSet.Change.Delete -> Unit
                    }
                  }

                  for ((filePath, change) in changeSet.changeByFilePath) {
                    if (filePath in handledPaths) {
                      continue
                    }

                    when (change) {
                      is AiCodePatcher.ChangeSet.Change.Create ->
                          put(filePath, CodeFileContent(code = change.content))
                      is AiCodePatcher.ChangeSet.Change.Patch ->
                          throw IllegalStateException(
                              "Cannot patch missing file ${filePath.toUnixRelativePathString()}"
                          )
                      AiCodePatcher.ChangeSet.Change.Delete -> Unit
                    }
                  }
                },
        )
  }

  interface ChangeApplier {
    suspend fun applyChanges(
        inputCodeCatalog: CodeCatalog,
    ): CodeCatalog
  }

  interface FileEditor {
    suspend fun editWithin(
        workingDirectory: MutableCompatFsDirectory,
    )
  }

  suspend fun attemptToCompleteTask(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      taskDescription: String,
  ): FileEditor

  suspend fun attemptToFixIssues(
      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
      originalTaskDescription: String,
      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
  ): FileEditor
}

private suspend fun MutableCompatFsDirectory.writeCodeFile(
    filePath: LiteralRelativeUnixPath,
    fileContent: CodeFileContent,
) {
  val existingEntity = extractDeepMutable(filePath)
  val fileName =
      filePath.names.lastOrNull()
          ?: throw IllegalArgumentException("Cannot write a file at the empty path")
  val parentPath = LiteralRelativeUnixPath(names = filePath.names.dropLast(1))

  when (existingEntity) {
    is MutableCompatFsFile -> {
      existingEntity.write(fileContent.dump().encodeToByteString())
      return
    }

    is MutableCompatFsDirectory -> {
      throw IllegalStateException(
          "Expected file at path ${filePath.toUnixRelativePathString()} to write, but found a directory",
      )
    }

    null -> {
      val parentDirectory = ensureDirectory(relativePath = parentPath)

      parentDirectory.createFile(
          name = fileName,
          initialContent = fileContent.dump().encodeToByteString(),
      )
    }
  }
}

private suspend fun MutableCompatFsDirectory.ensureDirectory(
    relativePath: LiteralRelativeUnixPath,
): MutableCompatFsDirectory {
  var currentDirectory: MutableCompatFsDirectory = this

  for (name in relativePath.names) {
    currentDirectory =
        when (val existingEntity = currentDirectory.extract(name)) {
          null -> currentDirectory.createDirectory(name)
          is MutableCompatFsDirectory -> existingEntity
          is MutableCompatFsFile -> {
            throw IllegalStateException(
                "Expected directory at path ${relativePath.toUnixRelativePathString()}, but found a file at ${name.name}",
            )
          }
        }
  }

  return currentDirectory
}

fun PatchGenerator.masking(
    masker: AiCodePatcher.CodeMasker,
): ChangeApplier =
    object : ChangeApplier {
      override suspend fun applyChanges(
          inputCodeCatalog: CodeCatalog,
      ): CodeCatalog {
        val maskedCodeCatalog =
            inputCodeCatalog.applyMasks(
                masker = masker,
            )

        val changeSet =
            this@masking.generateChanges(
                maskedCodeCatalog = maskedCodeCatalog,
            )

        return inputCodeCatalog.applyChangeSet(
            changeSet = changeSet,
        )
      }
    }

fun ChangeApplier.selecting(
    selector: AiCodeEditor.FileSelector,
): FileEditor =
    object : FileEditor {
      override suspend fun editWithin(
          workingDirectory: MutableCompatFsDirectory,
      ) {
        val pickedCodeCatalog =
            selector.pickFiles(
                sourceDirectory = workingDirectory,
            )

        val overlayCatalog =
            this@selecting.applyChanges(
                inputCodeCatalog = pickedCodeCatalog,
            )

        overlayCatalog.writeBack(
            targetDirectory = workingDirectory,
        )
      }
    }
