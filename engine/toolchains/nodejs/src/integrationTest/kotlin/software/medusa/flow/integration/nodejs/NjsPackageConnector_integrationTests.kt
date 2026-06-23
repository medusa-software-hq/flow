package software.medusa.flow.integration.nodejs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.nodejs.package_manager.NjsNpmConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnectorHub
import software.medusa.flow.integration.nodejs.package_manager.NjsYarnConnector
import software.medusa.flow.integration.nodejs.process.NjsProcessPackageConnector
import software.medusa.flow.test_utils.withMaterializedResource

/**
 * Drives the module through its public API against micro TypeScript projects copied from test
 * resources: it installs dependencies with the project's package manager, then resolves and runs
 * the project-local `tsc` to type-check the sources.
 *
 * Requires `npm`, `yarn`, and `node` on the PATH and network access to the package registry.
 */
class NjsPackageConnector_integrationTests {
  // Constructed up front: locating the npm and yarn executables fails fast if the system does not
  // meet the requirements, before any fixture is materialized.
  private val packageConnector: NjsPackageConnector =
      NjsProcessPackageConnector(
          packageManagerConnectorHub =
              NjsPackageManagerConnectorHub(
                  npmConnector =
                      NjsNpmConnector(
                          npmExecutableHandle = SysExecutableHandle.locate(commandName = "npm"),
                      ),
                  yarnConnector =
                      NjsYarnConnector(
                          yarnExecutableHandle = SysExecutableHandle.locate(commandName = "yarn"),
                      ),
              ),
          processSpawner = SysProcessSpawner(),
      )

  @Test
  fun `npm installs typescript and type-checks the project`() =
      runTest(timeout = installTimeout) {
        assertTypeChecks(
            fixturePath =
                UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal("npm-project")),
            packageManager = NjsPackageManager.Npm,
        )
      }

  @Test
  fun `yarn installs typescript and type-checks the project`() =
      runTest(timeout = installTimeout) {
        assertTypeChecks(
            fixturePath =
                UfsAbsolutePath.of(UfsName.Literal("fixtures"), UfsName.Literal("yarn-project")),
            packageManager = NjsPackageManager.Yarn,
        )
      }

  private suspend fun assertTypeChecks(
      fixturePath: UfsLiteralAbsolutePath,
      packageManager: NjsPackageManager,
  ) {
    withMaterializedResource(resourcePath = fixturePath) { packagePath ->
      val connection =
          packageConnector.connect(packagePath = packagePath, packageManager = packageManager)

      connection.installDependencies()

      val tsc = assertNotNull(connection.resolveCommand(name = UfsName.Literal("tsc")))

      // `index.ts` is relative: the resolved command runs in the package directory.
      val result = tsc.execute(arguments = listOf("--noEmit", "index.ts"))

      assertEquals(
          expected = 0,
          actual = result.exitCode,
          message = "tsc reported errors:\n${result.standardOutput}\n${result.errorOutput}",
      )
    }
  }

  private companion object {
    val installTimeout = 120.seconds
  }
}
