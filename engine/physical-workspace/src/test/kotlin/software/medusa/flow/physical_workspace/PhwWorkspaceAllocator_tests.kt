package software.medusa.flow.physical_workspace

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.integration.gradle.GrdProjectConnection
import software.medusa.flow.integration.nodejs.NjsPackageConnection
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManager
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator.Companion.allocateWorkspace

/**
 * Regression (flow: worker leaks per-session workspaces): `allocateWorkspace(templateDirectory)`
 * allocates a bare [PhwWorkspace] then materializes the template into it. Before this fix, a
 * cancelled session or a mid-copy I/O failure during that materialize step left the freshly
 * allocated workspace with no owner able to close it, orphaning its temp directory on disk.
 */
class PhwWorkspaceAllocator_tests {
  private class FakePhwWorkspaceAllocator(
      private val onAllocate: (File) -> Unit,
  ) : PhwWorkspaceAllocator {
    override suspend fun allocateWorkspace(): PhwWorkspace {
      val dir = createTempDirectory(prefix = "phw-workspace-").toFile()
      onAllocate(dir)
      return object : PhwWorkspace {
        override val rootDirectory = UfsNioDirectory(directoryPath = dir.toPath())

        override suspend fun connectNodeJs(
            packageManager: NjsPackageManager,
            packagePath: UfsLiteralAbsolutePath,
        ): NjsPackageConnection = error("not used by this test")

        override suspend fun connectGradle(
            projectPath: UfsLiteralAbsolutePath,
        ): GrdProjectConnection = error("not used by this test")

        override fun close() {
          dir.deleteRecursively()
        }
      }
    }
  }

  @Test
  fun `a failed materialize closes the freshly allocated workspace instead of leaking it`() =
      runBlocking {
        // A source directory that doesn't exist makes `materializeIn` fail deterministically.
        val missingSourceDirectory =
            createTempDirectory(prefix = "phw-missing-source-").toFile().also { it.delete() }

        var allocatedDir: File? = null
        val allocator = FakePhwWorkspaceAllocator(onAllocate = { allocatedDir = it })

        assertFailsWith<Exception> {
          allocator.allocateWorkspace(
              sourceRootDirectory =
                  UfsNioDirectory(directoryPath = missingSourceDirectory.toPath()),
          )
        }

        assertFalse(
            checkNotNull(allocatedDir) { "allocateWorkspace() must have run before materializing" }
                .exists(),
            "a failed materialize must close (and delete) the workspace it already allocated",
        )
      }
}
