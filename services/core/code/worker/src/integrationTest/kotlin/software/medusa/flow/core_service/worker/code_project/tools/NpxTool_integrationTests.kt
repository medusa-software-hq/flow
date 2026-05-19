package software.medusa.flow.core_service.worker.code_project.tools

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.resolve
import software.medusa.commons.paths.toAbsoluteNioPath
import software.medusa.commons.paths.toIoFile
import software.medusa.commons.paths.toLiteral

class NpxTool_integrationTests {
  @Test
  fun test_diagnose_returnsCorrect_forTypecheckableProject() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    try {
      val diagnosis =
          NpxTool(
                  projectPath = projectDirPath,
                  args = listOf("--yes", "--package", "typescript@6.0.3", "tsc", "--noEmit"),
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
  fun test_diagnose_returnsIncorrect_forBrokenTypescriptProject() = runTest {
    val projectDirPath = createFixtureProjectCopy()

    val sourceFilePath =
        LiteralRelativeUnixPath.of(
            UnixPath.Name.Literal("src"),
            UnixPath.Name.Literal("index.ts"),
        )

    try {
      Files.writeString(
          projectDirPath.resolve(sourceFilePath).toAbsoluteNioPath(),
          "const answer: string = 42\n",
      )

      val diagnosis =
          NpxTool(
                  projectPath = projectDirPath,
                  args = listOf("--yes", "--package", "typescript@6.0.3", "tsc", "--noEmit"),
              )
              .diagnose()

      val incorrectDiagnosis = assertIs<CodeTool.CodeModuleDiagnosis.Incorrect>(diagnosis)
      val singleEntry = assertNotNull(incorrectDiagnosis.diagnosisByFilePath.entries.singleOrNull())

      assertEquals(
          expected =
              projectDirPath.innerPath.let { innerPath ->
                LiteralRelativeUnixPath.of(innerPath.names.last())
              },
          actual = singleEntry.key,
      )

      assertEquals(
          expected = singleEntry.value.issues.singleOrNull()?.description?.isNotBlank(),
          actual = true,
      )
    } finally {
      projectDirPath.toIoFile().deleteRecursively()
    }
  }

  private fun createFixtureProjectCopy(): LiteralAbsoluteUnixPath {
    val projectDirPath = createTempDirectory(prefix = "npx-tool-fixture-").toRealPath()

    fixtureRelativePaths.forEach { relativePath ->
      val destinationPath = projectDirPath.resolve(relativePath)

      Files.createDirectories(destinationPath.parent)

      val resourceInputStream =
          checkNotNull(
              javaClass.classLoader.getResourceAsStream(
                  "fixtures/npm-typescript-project/$relativePath"
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
            "package.json",
            "tsconfig.json",
            "src/index.ts",
        )
  }
}
