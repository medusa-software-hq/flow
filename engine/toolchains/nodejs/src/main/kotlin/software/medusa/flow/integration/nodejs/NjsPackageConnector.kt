package software.medusa.flow.integration.nodejs

import java.nio.file.Path
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager

/** Establishes a generic connection to a Node.js project tree. */
interface NjsPackageConnector {
  /** Opens a connection for the project rooted at [packagePath]. */
  suspend fun connect(
      packagePath: Path,
      packageManager: NjsPackageManager,
  ): NjsPackageConnection
}
