package software.medusa.flow.integration.gradle

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

  private companion object {
    val fixturePath =
        UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal("gradle-project"))
  }
}
