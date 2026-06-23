package software.medusa.flow.integration.gradle

import java.nio.file.Path

/** Establishes a connection to a Gradle project. */
interface GrdProjectConnector {
  /** Connects to the Gradle project at [projectPath] and returns an active connection handle. */
  suspend fun connect(projectPath: Path): GrdProjectConnection
}
