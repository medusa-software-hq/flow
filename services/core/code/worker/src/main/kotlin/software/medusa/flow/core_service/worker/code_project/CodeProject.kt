package software.medusa.flow.core_service.worker.code_project

import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.commons.paths.LiteralRelativeUnixPath

interface CodeProject {
  @JvmInline
  value class ModuleLocator(
      val modulePath: LiteralRelativeUnixPath,
  )

  @JvmInline
  value class BulkCodeFileContent(
      val codeFileContentByPath: Map<LiteralRelativeUnixPath, TechFileContent.Code>,
  )

  val rootModule: CodeModule

  //  val formattingTool: CodeTool

  //  val verificationTool: CodeTool

  //  val workingDirectory: MutableCompatFsDirectory
}
