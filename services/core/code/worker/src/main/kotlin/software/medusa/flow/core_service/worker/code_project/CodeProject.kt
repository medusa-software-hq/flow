package software.medusa.flow.core_service.worker.code_project

import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.extractDeepMutable
import software.medusa.commons.filesystem.compat.extractDeepReadonly
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

interface CodeProject {
  @JvmInline
  value class ModuleLocator(
      val modulePath: LiteralRelativeUnixPath,
  )

  @JvmInline
  value class BulkCodeFileContent(
      val codeFileContentByPath: Map<LiteralRelativeUnixPath, CodeFileContent>,
  )

  sealed class FormattingResult {
    data class FoundSyntaxErrors(
        val formatterOutput: String,
    ) : FormattingResult()

    data object Formatted : FormattingResult()
  }

  sealed class AnalysisResult {
    data class Rejected(
        val analyzerOutput: String,
    ) : AnalysisResult()

    data object Accepted : AnalysisResult()
  }

  sealed class TestingResult {
    data class SomeFailed(
        val testingOutput: String,
    ) : TestingResult()

    data object AllPassed : TestingResult()
  }

  val formattingTool: CodeTool

  val verificationTool: CodeTool

  val workingDirectory: MutableCompatFsDirectory

  suspend fun format(): FormattingResult

  suspend fun analyze(): AnalysisResult

  suspend fun test(): TestingResult
}

suspend fun CodeProject.readFile(
    filePath: LiteralRelativeUnixPath,
): CodeFileContent {
  val fileEntity =
      workingDirectory.extractDeepReadonly(filePath) as? ReadonlyCompatFsFile
          ?: throw IllegalStateException(
              "Expected file at path ${filePath.toUnixRelativePathString()}"
          )

  return CodeFileContent.parse(
      rawContent = fileEntity.read().decodeToString(),
  )
}

suspend fun CodeProject.updateFile(
    filePath: LiteralRelativeUnixPath,
    newFileContent: CodeFileContent,
) {
  val targetEntity =
      workingDirectory.extractDeepMutable(
          relativePath = filePath,
      )
          ?: throw IllegalStateException(
              "Expected existing file at path ${filePath.toUnixRelativePathString()} to update"
          )

  when (targetEntity) {
    is MutableCompatFsFile -> {
      targetEntity.write(
          newContent = newFileContent.dump().encodeToByteString(),
      )
    }

    is MutableCompatFsDirectory -> {
      throw IllegalStateException(
          "Expected file at path ${filePath.toUnixRelativePathString()} to update, but found a directory"
      )
    }
  }
}
