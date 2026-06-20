package software.medusa.flow.integration.nodejs.process

import kotlin.io.path.exists
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.nodejs.NjsCommand
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnection

/**
 * Process-backed placeholder implementation of
 * [software.medusa.flow.integration.nodejs.NjsPackageConnection].
 */
internal class NjsProcessPackageConnection(
    private val processSpawner: SysProcessSpawner,
    private val packageManagerConnection: NjsPackageManagerConnection,
) : NjsPackageConnection {
  override suspend fun installDependencies() {
    packageManagerConnection.installDependencies(
        processSpawner = processSpawner,
    )
  }

  override suspend fun resolveCommand(
      name: UfsName.Literal,
  ): NjsCommand? {
    val executablePath =
        packageManagerConnection.packagePath.resolve("node_modules/.bin").resolve(name.content)

    if (!executablePath.exists()) {
      return null
    }

    return NjsProcessCommand(
        processSpawner = processSpawner,
        executable = SysExecutableHandle.resolve(executablePath = executablePath),
        workingDirectory = packageManagerConnection.packagePath,
    )
  }
}
