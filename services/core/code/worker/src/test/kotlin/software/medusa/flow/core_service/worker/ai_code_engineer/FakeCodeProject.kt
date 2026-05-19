package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.code_project.CodeModule
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class FakeCodeProject(
    override val rootModule: CodeModule =
        object : CodeModule {
          override val formattingTool: CodeTool = CodeTool.AlwaysCorrect

          override val verificationTool: CodeTool = CodeTool.AlwaysCorrect
        },
) : CodeProject {}
