package software.medusa.flow.core_service.worker.code_project.tools

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.resolve
import software.medusa.commons.paths.toIoFile
import software.medusa.commons.paths.toLiteral

/**
 * Test of the integration with Gradle.
 *
 * OpenRouter integration is tested separately on a different level.
 */
class GradleTool_integrationTests {
  @Test
  fun test_diagnose_returnsCorrect_forCompilableGradleProject() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    try {
      val diagnosis =
          GradleTool(
                  projectPath = projectDirPath,
                  taskName = "compileKotlin",
                  gradleOutputParser = NaiveGradleOutputParser,
              )
              .diagnose()

      assertEquals(
          expected = CodeTool.CodeModuleDiagnosis.Correct,
          actual = diagnosis,
      )
    } finally {
      projectDirPath.toIoFile().deleteRecursively()
    }
  }

  @Test
  fun test_diagnose_returnsIncorrect_forBrokenGradleProject() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    val appFilePath =
        LiteralRelativeUnixPath.of(
            UnixPath.Name.Literal("src"),
            UnixPath.Name.Literal("main"),
            UnixPath.Name.Literal("kotlin"),
            UnixPath.Name.Literal("App.kt"),
        )

    try {
      Files.writeString(
          projectDirPath.resolve(appFilePath).toIoFile().toPath(),
          "fun meaningOfLife(): Int =",
      )

      val diagnosis =
          GradleTool(
                  projectPath = projectDirPath,
                  taskName = "compileKotlin",
                  gradleOutputParser = NaiveGradleOutputParser,
              )
              .diagnose()

      val incorrectDiagnosis = assertIs<CodeTool.CodeModuleDiagnosis.Incorrect>(diagnosis)

      val singleEntry =
          assertNotNull(
              incorrectDiagnosis.diagnosisByFilePath.entries.singleOrNull(),
          )

      assertEquals(
          expected = appFilePath,
          actual = singleEntry.key,
      )

      assertTrue(
          actual = singleEntry.value.issues.any { issue -> issue.description.startsWith("[1:") },
      )
    } finally {
      projectDirPath.toIoFile().deleteRecursively()
    }
  }

  private fun createFixtureProjectCopy(): LiteralAbsoluteUnixPath {
    // Ensure the path is _real_, as on some systems (e.g. macOS) the temp directory involves
    // symlinks
    val projectDirPath = createTempDirectory(prefix = "gradle-tool-fixture-").toRealPath()

    fixtureRelativePaths.forEach { relativePath ->
      val destinationPath = projectDirPath.resolve(relativePath)

      Files.createDirectories(destinationPath.parent)

      val resourceInputStream =
          checkNotNull(
              javaClass.classLoader.getResourceAsStream(
                  "fixtures/gradle-kotlin-project/$relativePath"
              )
          ) {
            "Missing fixture resource: $relativePath"
          }

      Files.copy(resourceInputStream, destinationPath)
    }

    return AbsoluteUnixPath.parse(projectDirPath.toString()).toLiteral()
        ?: error("Temp project path must be literal: $projectDirPath")
  }

  companion object {
    private val fixtureRelativePaths =
        listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "src/main/kotlin/App.kt",
        )
  }
}
