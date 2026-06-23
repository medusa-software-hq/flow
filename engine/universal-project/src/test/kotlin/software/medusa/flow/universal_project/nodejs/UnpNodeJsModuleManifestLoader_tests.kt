package software.medusa.flow.universal_project.nodejs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.universal_project.FakePhysicalWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.absolutePath
import software.medusa.flow.universal_project.createModule

class UnpNodeJsModuleManifestLoader_tests {
  private val manifestYaml =
      """
      package-manager: yarn
      bootstrap:
        steps:
          - name: Generate
            command: codegen
      analyze:
        steps:
          - name: Lint
            command: lint
      test:
        steps:
          - name: Test
            command: test
      """
          .trimIndent()

  @Test
  fun `bootstrap generates a file and analyze validates the sources`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val frontendDirectory =
        workspaceRoot.createModule(
            name = "frontend",
            manifestFileName = "nodejs.module.yaml",
            manifestYaml = manifestYaml,
            sourceFileNames = listOf("main.ts", "util.ts"),
        )

    val connection =
        UnpNodeJsModuleManifestLoader.load(frontendDirectory)
            .connect(
                physicalWorkspace = FakePhysicalWorkspace(rootDirectory = workspaceRoot),
                modulePath = absolutePath("/frontend"),
            )

    assertNull(frontendDirectory.extract(UfsName.Literal("generated.txt")))

    assertEquals(UnpModuleConnection.Result.Success, connection.bootstrap())

    // The bootstrap step actually generated a file in the workspace.
    assertNotNull(frontendDirectory.extract(UfsName.Literal("generated.txt")))

    assertEquals(UnpModuleConnection.Result.Success, connection.analyze())
    assertEquals(UnpModuleConnection.Result.Success, connection.test())
  }

  @Test
  fun `analyze fails when a source name is not lowercase`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val frontendDirectory =
        workspaceRoot.createModule(
            name = "frontend",
            manifestFileName = "nodejs.module.yaml",
            manifestYaml = manifestYaml,
            sourceFileNames = listOf("Main.ts"),
        )

    val connection =
        UnpNodeJsModuleManifestLoader.load(frontendDirectory)
            .connect(
                FakePhysicalWorkspace(rootDirectory = workspaceRoot),
                absolutePath("/frontend"),
            )

    val result = assertIs<UnpModuleConnection.Result.Failure>(connection.analyze())

    assertContains(result.diagnosticOutput, "Main.ts")
  }

  @Test
  fun `connecting fails when a referenced command is not installed`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val frontendDirectory =
        workspaceRoot.createModule(
            name = "frontend",
            manifestFileName = "nodejs.module.yaml",
            manifestYaml =
                """
                package-manager: yarn
                analyze:
                  steps:
                    - name: Unknown
                      command: nonexistent
                """
                    .trimIndent(),
        )

    assertFailsWith<IllegalArgumentException> {
      UnpNodeJsModuleManifestLoader.load(frontendDirectory)
          .connect(FakePhysicalWorkspace(rootDirectory = workspaceRoot), absolutePath("/frontend"))
    }
  }
}
