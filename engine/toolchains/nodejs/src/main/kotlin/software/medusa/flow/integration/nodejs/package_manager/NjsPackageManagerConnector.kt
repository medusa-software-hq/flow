package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path

internal interface NjsPackageManagerConnector {
  suspend fun connect(
      packagePath: Path,
  ): NjsPackageManagerConnection
}
