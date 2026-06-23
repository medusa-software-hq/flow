package software.medusa.flow.universal_project

import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory

interface UnpModuleManifestLoader {
  suspend fun load(
      moduleDirectory: UfsReadonlyDirectory,
  ): UnpModuleManifest
}
