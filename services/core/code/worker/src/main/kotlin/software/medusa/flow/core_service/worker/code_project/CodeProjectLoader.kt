package software.medusa.flow.core_service.worker.code_project

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.paths.LiteralAbsoluteUnixPath

interface CodeProjectLoader {
  suspend fun loadProject(
      projectDirectory: ReadonlyCompatFsDirectory,
      projectPath: LiteralAbsoluteUnixPath,
  ): CodeProject
}
