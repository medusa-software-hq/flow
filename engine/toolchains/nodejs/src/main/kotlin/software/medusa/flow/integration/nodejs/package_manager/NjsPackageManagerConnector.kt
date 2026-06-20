package software.medusa.flow.integration.nodejs.package_manager

import java.nio.file.Path

abstract class NjsPackageManagerConnector {
  internal abstract suspend fun connect(
      packagePath: Path,
  ): NjsPackageManagerConnection
}
