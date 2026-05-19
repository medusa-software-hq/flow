package software.medusa.flow.core_service.worker.code_project

import software.medusa.commons.paths.LiteralAbsoluteUnixPath

interface CodeProjectLoader {
  fun loadProject(
      projectPath: LiteralAbsoluteUnixPath,
  ): CodeProject
}
