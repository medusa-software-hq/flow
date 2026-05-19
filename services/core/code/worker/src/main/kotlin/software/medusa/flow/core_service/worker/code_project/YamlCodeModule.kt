package software.medusa.flow.core_service.worker.code_project

import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class YamlCodeModule(
    override val formattingTool: CodeTool,
    override val verificationTool: CodeTool,
    val submodulesByName: Map<String, YamlCodeModule>,
) : CodeModule
