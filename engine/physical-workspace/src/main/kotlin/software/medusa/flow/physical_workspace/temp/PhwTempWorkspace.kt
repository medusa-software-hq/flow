package software.medusa.flow.physical_workspace.temp

import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsRelativePath.Companion.toRelativeNioPath
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwConnectorHub
import software.medusa.flow.physical_workspace.PhwWorkspace

class PhwTempWorkspace(
    private val coroutineScope: CoroutineScope,
    private val connectorHub: PhwConnectorHub,
    private val tempDirectoryPath: Path,
) : PhwWorkspace {
  override val rootDirectory: UfsMutableDirectory by lazy {
    UfsNioDirectory(directoryPath = tempDirectoryPath)
  }

  override suspend fun connectNodeJs(
      packageManager: NjsPackageManager,
      packagePath: UfsLiteralAbsolutePath,
  ): NjsPackageConnection =
      connectorHub.nodeJsPackageConnector.connect(
          packagePath = resolveWithinWorkspace(workspacePath = packagePath),
          packageManager = packageManager,
      )

  override suspend fun connectGradle(
      projectPath: UfsLiteralAbsolutePath,
  ): GrdProjectConnection =
      connectorHub.gradleProjectConnector.connect(
          projectPath = resolveWithinWorkspace(workspacePath = projectPath),
      )

  /** Resolves a workspace-absolute path against the workspace root on the real filesystem. */
  private fun resolveWithinWorkspace(
      workspacePath: UfsLiteralAbsolutePath,
  ): Path = tempDirectoryPath.resolve(workspacePath.innerPath.toRelativeNioPath())

  override fun close() {
    coroutineScope.launch {
      withContext(Dispatchers.IO) { tempDirectoryPath.toFile().deleteRecursively() }
    }
  }
}
