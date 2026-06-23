package software.medusa.flow.universal_project

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.physical_workspace.PhwWorkspace

/** The parsed definition of a single module, independent of any concrete workspace. */
interface UnpModuleManifest {
  /**
   * Binds this module to [physicalWorkspace] at [modulePath], performing any toolchain
   * prerequisites (such as installing dependencies) and returning a connection ready to run
   * lifecycle phases.
   */
  suspend fun connect(
      physicalWorkspace: PhwWorkspace,
      modulePath: UfsLiteralAbsolutePath,
  ): UnpModuleConnection
}
