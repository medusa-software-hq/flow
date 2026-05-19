package software.medusa.flow.core_service.worker.code_project

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.toIoFile
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.flow.core_service.worker.code_project.tools.NaiveGradleOutputParser

class YamlCodeProjectLoader_integrationTests {
  @Test
  fun test_loadProject_buildsWorkingYamlBackedProject() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    try {
      val project =
          YamlCodeProjectLoader(
                  gradleOutputParser = NaiveGradleOutputParser,
                  npxOutputParser = null,
              )
              .loadProject(projectPath = projectDirPath)

      assertIs<YamlCodeProject>(project)
      assertEquals(
          expected = CodeTool.CodeModuleDiagnosis.Correct,
          actual = project.rootModule.formattingTool.diagnose(),
      )
      assertEquals(
          expected = CodeTool.CodeModuleDiagnosis.Correct,
          actual = project.rootModule.verificationTool.diagnose(),
      )
      assertEquals(
          expected = CodeTool.CodeModuleDiagnosis.Correct,
          actual = project.rootModule.verificationTool.diagnose(),
      )
      assertEquals(
          expected = setOf("backend", "frontend"),
          actual = project.rootModule.submodulesByName.keys,
      )
    } finally {
      projectDirPath.toIoFile().deleteRecursively()
    }
  }

  @Test
  fun test_loadProject_reportsVerificationFailure_fromBrokenFrontendSubmodule() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    try {
      Files.writeString(
          projectDirPath.toIoFile().toPath().resolve("frontend/src/index.ts"),
          "const answer: string = 42\n",
      )

      val project =
          YamlCodeProjectLoader(
                  gradleOutputParser = NaiveGradleOutputParser,
                  npxOutputParser = null,
              )
              .loadProject(projectPath = projectDirPath)

      val rejected =
          assertIs<CodeTool.CodeModuleDiagnosis.Incorrect>(
              project.rootModule.verificationTool.diagnose()
          )

      assertTrue(
          actual =
              rejected.diagnosisByFilePath.values
                  .flatMap { diagnosis -> diagnosis.issues }
                  .isNotEmpty(),
      )
    } finally {
      projectDirPath.toIoFile().deleteRecursively()
    }
  }

  private fun createFixtureProjectCopy(): LiteralAbsoluteUnixPath {
    val projectDirPath = createTempDirectory(prefix = "yaml-code-project-fixture-").toRealPath()

    fixtureRelativePaths.forEach { relativePath ->
      val destinationPath = projectDirPath.resolve(relativePath)

      Files.createDirectories(destinationPath.parent)

      val resourceInputStream =
          checkNotNull(
              javaClass.classLoader.getResourceAsStream("fixtures/yaml-code-project/$relativePath")
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
            "module.yaml",
            "backend/module.yaml",
            "backend/settings.gradle.kts",
            "backend/build.gradle.kts",
            "backend/src/main/kotlin/App.kt",
            "frontend/module.yaml",
            "frontend/package.json",
            "frontend/tsconfig.json",
            "frontend/src/index.ts",
        )
  }
}
