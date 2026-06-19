package software.medusa.flow.integration.gradle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Runs real Gradle builds through [GrdProperProjectConnector]/[GrdProperProjectConnection] against
 * a generated fixture project. The fixture pins the same Gradle distribution this repository
 * already uses, so the Tooling API reuses the cached distribution rather than downloading one.
 */
class GrdProperProjectConnection_integrationTests {
  @Test
  fun `runs a passing task and captures its output`() = runBlocking {
    withFixtureProject { projectPath ->
      GrdProperProjectConnector().connect(projectPath = projectPath).use { connection ->
        val result = connection.runTask(taskName = GrdTaskName(name = "hello"))

        assertEquals(GrdTaskResult.Status.Success, result.status)
        assertContains(result.standardOutput, "hello from gradle")
      }
    }
  }

  @Test
  fun `reports a failing task as a Failure`() = runBlocking {
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
      projectPath.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"\n")

      projectPath
          .resolve("build.gradle.kts")
          .writeText(
              """
              tasks.register("hello") { doLast { println("hello from gradle") } }
              tasks.register("boom") { doLast { throw GradleException("boom") } }
              """
                  .trimIndent() + "\n",
          )

      val wrapperDirectory = projectPath.resolve("gradle/wrapper")
      wrapperDirectory.createDirectories()
      wrapperDirectory
          .resolve("gradle-wrapper.properties")
          .writeText(
              """
              distributionBase=GRADLE_USER_HOME
              distributionPath=wrapper/dists
              distributionUrl=https://services.gradle.org/distributions/gradle-9.5.1-bin.zip
              zipStoreBase=GRADLE_USER_HOME
              zipStorePath=wrapper/dists
              validateDistributionUrl=false
              """
                  .trimIndent() + "\n",
          )

      block(projectPath)
    } finally {
      projectPath.toFile().deleteRecursively()
    }
  }
}
