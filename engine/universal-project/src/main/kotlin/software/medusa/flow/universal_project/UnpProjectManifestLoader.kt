package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory

interface UnpProjectManifestLoader {
  suspend fun load(
      projectDirectory: UfsReadonlyDirectory,
  ): UnpProjectManifest
}
