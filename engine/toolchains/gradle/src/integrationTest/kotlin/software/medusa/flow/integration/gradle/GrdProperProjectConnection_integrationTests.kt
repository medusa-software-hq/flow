package software.medusa.flow.integration.gradle

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.test_utils.withMaterializedResource

/**
 * Runs real Gradle builds through [GrdProperProjectConnector]/[GrdProperProjectConnection] against
 * a fixture project materialized from test resources. The fixture pins the same Gradle distribution
 * this repository already uses, so the Tooling API reuses the cached distribution rather than
 * downloading one.
 */
class GrdProperProjectConnection_integrationTests {
  @Test
  fun `runs a passing task and captures its output`() = runTest {
    withMaterializedResource(resourcePath = fixturePath) { projectPath ->
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val result = connection.runTask(taskName = GrdTaskName(name = "hello"))

        assertEquals(GrdTaskResult.Status.Success, result.status)
        assertContains(result.standardOutput, "hello from gradle")
      }
    }
  }

  @Test
  fun `reports a failing task as a Failure`() = runTest {
    withMaterializedResource(resourcePath = fixturePath) { projectPath ->
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val result = connection.runTask(taskName = GrdTaskName(name = "boom"))

        assertEquals(GrdTaskResult.Status.Failure, result.status)
      }
    }
  }

  @Test
  fun `self-heals a truncated cache jar left behind by a killed build`() = runTest {
    withMaterializedResource(resourcePath = fixturePath) { projectPath ->
      val cacheJar = projectPath.resolve("fake-cache").resolve("fixture-dep.jar")

      // Prime the cache: the first run has nothing to read, so the task writes a valid jar,
      // exactly like a build that completed its download normally in an earlier session.
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val primingResult = connection.runTask(taskName = GrdTaskName(name = "readCachedJar"))
        assertEquals(GrdTaskResult.Status.Success, primingResult.status)
      }

      // Simulate a build killed mid-download: the jar is left on disk but truncated.
      Files.write(cacheJar, byteArrayOf(0x50, 0x4b))

      // A later session (a fresh connection, mirroring a new worker session) reads the same
      // poisoned cache. Without self-healing this fails every time until someone clears the cache
      // by hand; the connection should instead evict the truncated jar and retry transparently.
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val recoveredResult = connection.runTask(taskName = GrdTaskName(name = "readCachedJar"))

        assertEquals(GrdTaskResult.Status.Success, recoveredResult.status)
      }
    }
  }

  private companion object {
    val fixturePath =
        UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal("gradle-project"))
  }
}
