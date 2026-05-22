package software.medusa.flow.core_service.worker.code_project

import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

interface CodeModule {
  val formattingTool: CodeTool

  val verificationTool: CodeTool
}
