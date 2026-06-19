package software.medusa.flow.integration.nodejs.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnection

class NjsProcessPackageConnection_tests {
  @Test
  fun `resolveCommand returns null when local executable is missing`() = runTest {
    val packageDirectoryPath = Files.createTempDirectory("njs-package-")

    try {
      val connection =
          NjsProcessPackageConnection(
              processSpawner = SysProcessSpawner(),
              packageManagerConnection = FakePackageManagerConnection(packageDirectoryPath),
          )

      val command = connection.resolveCommand(name = UfsName.Literal("eslint"))

      assertNull(command)
    } finally {
      packageDirectoryPath.toFile().deleteRecursively()
    }
  }

  @Test
  fun `resolveCommand finds project local executable and executes it`() = runTest {
    val packageDirectoryPath = Files.createTempDirectory("njs-package-")
    val executablePath = packageDirectoryPath.resolve("node_modules/.bin/hello")

    try {
      Files.createDirectories(checkNotNull(executablePath.parent))
      Files.writeString(
          executablePath,
          "#!/bin/sh\nprintf 'hello %s' \"$1\"\n",
      )
      Files.setPosixFilePermissions(
          executablePath,
          PosixFilePermissions.fromString("rwxr-xr-x"),
      )

      val connection =
          NjsProcessPackageConnection(
              processSpawner = SysProcessSpawner(),
              packageManagerConnection = FakePackageManagerConnection(packageDirectoryPath),
          )

      val command = assertNotNull(connection.resolveCommand(name = UfsName.Literal("hello")))
      val result = command.execute(arguments = listOf("world"))

      assertEquals(0, result.exitCode)
      assertEquals("hello world", result.standardOutput)
      assertEquals("", result.errorOutput)
    } finally {
      packageDirectoryPath.toFile().deleteRecursively()
    }
  }

  private class FakePackageManagerConnection(
      override val packagePath: Path,
  ) : NjsPackageManagerConnection {
    override suspend fun installDependencies(
        processSpawner: SysProcessSpawner,
    ) = Unit
  }
}
