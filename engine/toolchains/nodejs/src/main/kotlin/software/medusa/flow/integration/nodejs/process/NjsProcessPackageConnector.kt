package software.medusa.flow.integration.nodejs.process

import java.nio.file.Path
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.integration.nodejs.NjsPackageConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnectorHub

internal class NjsProcessPackageConnector(
    private val packageManagerConnectorHub: NjsPackageManagerConnectorHub,
    private val processSpawner: SysProcessSpawner,
) : NjsPackageConnector {
  override suspend fun connect(
      packagePath: Path,
      packageManager: NjsPackageManager,
  ): NjsProcessPackageConnection {
    val packageManagerConnection =
        packageManager
            .selectConnector(
                connectorHub = packageManagerConnectorHub,
            )
            .connect(
                packagePath = packagePath,
            )

    return NjsProcessPackageConnection(
        processSpawner = processSpawner,
        packageManagerConnection = packageManagerConnection,
    )
  }
}
