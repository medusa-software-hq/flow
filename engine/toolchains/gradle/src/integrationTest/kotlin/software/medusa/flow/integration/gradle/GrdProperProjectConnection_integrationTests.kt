package software.medusa.flow.integration.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Runs real Gradle builds through [GrdProperProjectConnector]/[GrdProperProjectConnection] against
 * a fixture project copied from test resources into a temporary directory. The fixture pins the
 * same Gradle distribution this repository already uses, so the Tooling API reuses the cached
 * distribution rather than downloading one.
 */
class GrdProperProjectConnection_integrationTests {
  @Test
  fun `runs a passing task and captures its output`() = runTest {
    withFixtureProject { projectPath ->
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val result = connection.runTask(taskName = GrdTaskName(name = "hello"))

        assertEquals(GrdTaskResult.Status.Success, result.status)
        assertContains(result.standardOutput, "hello from gradle")
      }
    }
  }

  @Test
  fun `reports a failing task as a Failure`() = runTest {
    withFixtureProject { projectPath ->
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val result = connection.runTask(taskName = GrdTaskName(name = "boom"))

        assertEquals(GrdTaskResult.Status.Failure, result.status)
      }
    }
  }

  private suspend fun withFixtureProject(
      block: suspend (Path) -> Unit,
  ) {
    val projectPath = Files.createTempDirectory("grd-fixture-")

    try {
      copyResourceTree(resourcePath = fixtureResourcePath, targetDirectory = projectPath)
      block(projectPath)
    } finally {
      projectPath.toFile().deleteRecursively()
    }
  }

  private fun copyResourceTree(
      resourcePath: String,
      targetDirectory: Path,
  ) {
    val resourceUrl =
        checkNotNull(javaClass.getResource(resourcePath)) { "Missing test fixture: $resourcePath" }
    val sourceDirectory = Paths.get(resourceUrl.toURI())

    Files.walk(sourceDirectory).use { entries ->
      entries.forEach { sourceEntry ->
        val targetEntry =
            targetDirectory.resolve(sourceDirectory.relativize(sourceEntry).toString())

        if (Files.isDirectory(sourceEntry)) {
          Files.createDirectories(targetEntry)
        } else {
          Files.createDirectories(checkNotNull(targetEntry.parent))
          Files.copy(sourceEntry, targetEntry)
        }
      }
    }
  }

  private companion object {
    const val fixtureResourcePath = "/fixtures/gradle-project"
  }
}
