package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner

class NjsNpmConnector(
    private val npmExecutableHandle: SysExecutableHandle,
) : NjsPackageManagerConnector() {
  internal inner class Connection(
      override val packagePath: Path,
  ) : NjsPackageManagerConnection {
    override suspend fun installDependencies(
        processSpawner: SysProcessSpawner,
    ) {
      processSpawner.runInstallOrThrow(
          label = "npm",
          executable = npmExecutableHandle,
          workingDirectory = packagePath,
          arguments = listOf("ci"),
      )
    }
  }

  override suspend fun connect(
      packagePath: Path,
  ): Connection = Connection(packagePath = packagePath)
}
