package software.medusa.flow.universal_project.gradle

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

class UnpGradleModuleManifestLoader_tests {
  private val manifestYaml =
      """
      bootstrap:
        steps:
          - name: Generate
            task: generate
      analyze:
        steps:
          - name: Assemble
            task: assemble
          - name: Lint
            task: lint
      test:
        steps:
          - name: Test
            task: test
      """
          .trimIndent()

  @Test
  fun `bootstrap generates a file and analyze validates the sources`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val backendDirectory =
        workspaceRoot.createModule(
            name = "backend",
            manifestFileName = "gradle.module.yaml",
            manifestYaml = manifestYaml,
            sourceFileNames = listOf("app.kt", "util.kt"),
        )

    val connection =
        UnpGradleModuleManifestLoader.load(backendDirectory)
            .connect(
                physicalWorkspace = FakePhysicalWorkspace(rootDirectory = workspaceRoot),
                modulePath = absolutePath("/backend"),
            )

    assertNull(backendDirectory.extract(UfsName.Literal("generated.txt")))

    assertEquals(UnpModuleConnection.Result.Success, connection.bootstrap())

    // The bootstrap step actually generated a file in the workspace.
    assertNotNull(backendDirectory.extract(UfsName.Literal("generated.txt")))

    assertEquals(UnpModuleConnection.Result.Success, connection.analyze())
    assertEquals(UnpModuleConnection.Result.Success, connection.test())
  }

  @Test
  fun `analyze fails when the lint task finds a non-lowercase source`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val backendDirectory =
        workspaceRoot.createModule(
            name = "backend",
            manifestFileName = "gradle.module.yaml",
            manifestYaml = manifestYaml,
            sourceFileNames = listOf("App.kt"),
        )

    val connection =
        UnpGradleModuleManifestLoader.load(backendDirectory)
            .connect(FakePhysicalWorkspace(rootDirectory = workspaceRoot), absolutePath("/backend"))

    val result = assertIs<UnpModuleConnection.Result.Failure>(connection.analyze())

    assertContains(result.diagnosticOutput, "App.kt")
  }

  @Test
  fun `a phase the manifest omits succeeds as a no-op`() = runTest {
    val workspaceRoot = UfsMemoryDirectory()
    val backendDirectory =
        workspaceRoot.createModule(
            name = "backend",
            manifestFileName = "gradle.module.yaml",
            manifestYaml = manifestYaml,
            sourceFileNames = listOf("App.kt"),
        )

    val connection =
        UnpGradleModuleManifestLoader.load(backendDirectory)
            .connect(FakePhysicalWorkspace(rootDirectory = workspaceRoot), absolutePath("/backend"))

    // The manifest declares no normalize phase, so it passes regardless of the (offending) sources.
    assertEquals(UnpModuleConnection.Result.Success, connection.normalize())
  }
}
