package software.medusa.flow.universal_project

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.physical_workspace.PhwWorkspace

/**
 * The parsed, toolchain-agnostic definition of a universal project: the modules it is made of,
 * keyed by their path within the project.
 */
data class UnpProjectManifest(
    val moduleManifestByPath: Map<UfsLiteralAbsolutePath, UnpModuleManifest>,
) {
  /** Binds every module against [physicalWorkspace], yielding a connection ready to run phases. */
  suspend fun connect(
      physicalWorkspace: PhwWorkspace,
  ): UnpProjectConnection =
      UnpProjectConnection(
          moduleConnectionByPath =
              moduleManifestByPath.mapValues { (modulePath, moduleManifest) ->
                moduleManifest.connect(
                    physicalWorkspace = physicalWorkspace,
                    modulePath = modulePath,
                )
              },
      )
}
