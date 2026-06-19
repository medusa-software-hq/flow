package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

internal class NjsYarnConnector(
    private val yarnExecutableHandle: SysExecutableHandle,
) : NjsPackageManagerConnector {
  inner class Connection(
      override val packagePath: Path,
  ) : NjsPackageManagerConnection {
    override suspend fun installDependencies(
        processSpawner: SysProcessSpawner,
    ) {
      processSpawner.spawn(
          executable = yarnExecutableHandle,
          workingDirectory = packagePath,
          arguments = listOf("install", "--frozen-lockfile"),
      )
    }
  }

  override suspend fun connect(
      packagePath: Path,
  ): Connection = Connection(packagePath = packagePath)
}
