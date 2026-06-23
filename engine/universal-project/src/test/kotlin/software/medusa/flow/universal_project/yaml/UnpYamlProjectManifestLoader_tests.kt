package software.medusa.flow.universal_project.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.universal_project.FakePhysicalWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.absolutePath
import software.medusa.flow.universal_project.createModule
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader

class UnpYamlProjectManifestLoader_tests {
  private val loader =
      UnpYamlProjectManifestLoader(
          gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
          nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
      )

  private val projectYaml =
      """
      modules:
        - path: /backend
          name: Backend
          kind: gradle
        - path: /frontend
          name: Frontend
          kind: nodejs
      """
          .trimIndent()

  private val gradleManifestYaml =
      """
      analyze:
        steps:
          - name: Lint
            task: lint
      """
          .trimIndent()

  private val nodeJsManifestYaml =
      """
      package-manager: yarn
      analyze:
        steps:
          - name: Lint
            command: lint
      """
          .trimIndent()

  private suspend fun workspaceWith(
      backendSourceFileNames: List<String>,
      frontendSourceFileNames: List<String>,
  ): FakePhysicalWorkspace {
    val workspaceRoot = UfsMemoryDirectory()

    workspaceRoot.createFile(
        name = UfsName.Literal("project.yaml"),
        initialContent = projectYaml.encodeToByteString(),
    )

    workspaceRoot.createModule(
        name = "backend",
        manifestFileName = "gradle.module.yaml",
        manifestYaml = gradleManifestYaml,
        sourceFileNames = backendSourceFileNames,
    )

    workspaceRoot.createModule(
        name = "frontend",
        manifestFileName = "nodejs.module.yaml",
        manifestYaml = nodeJsManifestYaml,
        sourceFileNames = frontendSourceFileNames,
    )

    return FakePhysicalWorkspace(rootDirectory = workspaceRoot)
  }

  @Test
  fun `loads both modules and analyzes them jointly`() = runTest {
    val workspace =
        workspaceWith(
            backendSourceFileNames = listOf("app.kt"),
            frontendSourceFileNames = listOf("main.ts"),
        )

    val manifest = loader.load(workspace.rootDirectory)

    assertEquals(
        setOf(absolutePath("/backend"), absolutePath("/frontend")),
        manifest.moduleManifestByPath.keys,
    )

    assertEquals(
        UnpProjectConnection.JointResult.Success,
        manifest.connect(workspace).analyzeAll(),
    )
  }

  @Test
  fun `a module with an offending source yields a joint failure for its path`() = runTest {
    val workspace =
        workspaceWith(
            backendSourceFileNames = listOf("App.kt"),
            frontendSourceFileNames = listOf("main.ts"),
        )

    val result =
        assertIs<UnpProjectConnection.JointResult.Failure>(
            loader.load(workspace.rootDirectory).connect(workspace).analyzeAll(),
        )

    assertEquals(setOf(absolutePath("/backend")), result.failureByModulePath.keys)
  }
}
