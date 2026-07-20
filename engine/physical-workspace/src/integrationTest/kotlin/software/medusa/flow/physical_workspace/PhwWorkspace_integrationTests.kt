package software.medusa.flow.physical_workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.gradle.GrdProperProjectConnector
import software.medusa.flow.integration.gradle.GrdTaskName
import software.medusa.flow.integration.gradle.GrdTaskResult
import software.medusa.flow.integration.nodejs.package_manager.NjsNpmConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnectorHub
import software.medusa.flow.integration.nodejs.package_manager.NjsYarnConnector
import software.medusa.flow.integration.nodejs.process.NjsProcessPackageConnector
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator.Companion.allocateWorkspace
import software.medusa.flow.physical_workspace.temp.PhwTempWorkspaceAllocator
import software.medusa.flow.test_utils.withMaterializedResource

/**
 * Allocates a real temporary workspace from a fixture monorepo and drives both toolchains against
 * it: it type-checks/compiles the Gradle/Kotlin `backend` and installs the yarn `frontend`.
 *
 * The fixture uses yarn for the JS side, but both `npm` and `yarn` are located up front, per the
 * package-manager connector hub's contract. Requires `npm`, `yarn`, and `node` on the PATH and
 * network access; the backend's Gradle distribution is pinned to the one this repository caches.
 */
class PhwWorkspace_integrationTests {
  // Built up front: locating the executables fails fast if the system does not meet requirements,
  // before any workspace is allocated.
  private val connectorHub =
      PhwConnectorHub(
          gradleProjectConnector = GrdProperProjectConnector(),
          nodeJsPackageConnector =
              NjsProcessPackageConnector(
                  packageManagerConnectorHub =
                      NjsPackageManagerConnectorHub(
                          npmConnector =
                              NjsNpmConnector(
                                  npmExecutableHandle =
                                      SysExecutableHandle.locate(commandName = "npm"),
                              ),
                          yarnConnector =
                              NjsYarnConnector(
                                  yarnExecutableHandle =
                                      SysExecutableHandle.locate(commandName = "yarn"),
                              ),
                      ),
                  processSpawner = SysProcessSpawner(),
              ),
      )

  @Test
  fun `allocates a workspace and drives both toolchains`() =
      runTest(timeout = 5.minutes) {
        val allocator =
            PhwTempWorkspaceAllocator(coroutineScope = this, connectorHub = connectorHub)

        withMaterializedResource(resourcePath = monorepoFixturePath) { sourcePath ->
          allocator
              .allocateWorkspace(
                  sourceRootDirectory = UfsNioDirectory(directoryPath = sourcePath),
              )
              .use { workspace ->
                val backendCompilation =
                    workspace.connectGradle(projectPath = backendPath).use { gradle ->
                      gradle.runTask(taskName = GrdTaskName(name = "compileKotlin"))
                    }

                assertEquals(GrdTaskResult.Status.Success, backendCompilation.status)

                workspace
                    .connectNodeJs(
                        packageManager = NjsPackageManager.Yarn,
                        packagePath = frontendPath,
                    )
                    .installDependencies()
              }
        }
      }

  private companion object {
    val monorepoFixturePath =
        UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal("monorepo"))

    val backendPath = UfsAbsolutePath.of(UfsName.Literal("backend"))

    val frontendPath = UfsAbsolutePath.of(UfsName.Literal("frontend"))
  }
}
