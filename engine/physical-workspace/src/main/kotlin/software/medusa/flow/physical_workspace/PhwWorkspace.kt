package software.medusa.flow.physical_workspace

import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager

interface PhwWorkspace : AutoCloseable {
  val rootDirectory: UfsMutableDirectory

  /**
   * Connects to the Node.js package living at [packagePath].
   *
   * [packagePath] is absolute within the workspace: it is anchored at the workspace root, so
   * `/frontend` refers to the `frontend` directory directly beneath this workspace.
   */
  suspend fun connectNodeJs(
      packageManager: NjsPackageManager,
      packagePath: UfsLiteralAbsolutePath,
  ): NjsPackageConnection

  /**
   * Connects to the Gradle project living at [projectPath].
   *
   * [projectPath] is absolute within the workspace: it is anchored at the workspace root, so
   * `/backend` refers to the `backend` directory directly beneath this workspace.
   */
  suspend fun connectGradle(
      projectPath: UfsLiteralAbsolutePath,
  ): GrdProjectConnection
}
