package software.medusa.flow.core_service.worker.code_project

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code.CodeFileContent

interface CodeProject {
  @JvmInline
  value class ModuleLocator(
      val modulePath: LiteralRelativeUnixPath,
  )

  @JvmInline
  value class BulkCodeFileContent(
      val codeFileContentByPath: Map<LiteralRelativeUnixPath, CodeFileContent>,
  )

  val rootModule: CodeModule

  //  val formattingTool: CodeTool

  //  val verificationTool: CodeTool

  //  val workingDirectory: MutableCompatFsDirectory
}
