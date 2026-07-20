package software.medusa.flow.integration.gradle

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.GradleConnector

/** Connects to a regular on-disk Gradle project through the Gradle Tooling API. */
class GrdProperProjectConnector : GrdProjectConnector {
  /** Opens a Tooling API connection to the Gradle project rooted at [projectPath]. */
  override suspend fun connect(projectPath: Path): GrdProjectConnection =
      withContext(Dispatchers.IO) {
        val projectConnection =
            GradleConnector.newConnector().forProjectDirectory(projectPath.toFile()).connect()

        GrdProperProjectConnection(projectConnection = projectConnection)
      }
}
