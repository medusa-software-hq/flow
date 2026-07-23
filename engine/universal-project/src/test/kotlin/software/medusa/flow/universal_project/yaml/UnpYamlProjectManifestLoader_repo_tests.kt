package software.medusa.flow.universal_project.yaml

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.universal_project.absolutePath
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader

/**
 * Loads Flow's own `project.yaml`, so a broken repo manifest fails this suite, not just dogfooding.
 */
class UnpYamlProjectManifestLoader_repo_tests {
  private val loader =
      UnpYamlProjectManifestLoader(
          gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
          nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
      )

  @Test
  fun `the repo's own project manifest parses into its declared modules`() = runTest {
    val repoRoot = UfsNioDirectory(directoryPath = Path.of(System.getProperty("flow.repoRoot")))

    val manifest = loader.load(projectDirectory = repoRoot)

    assertEquals(
        setOf(absolutePath("/"), absolutePath("/apps/web/spa/frontend")),
        manifest.moduleManifestByPath.keys,
    )
  }
}
