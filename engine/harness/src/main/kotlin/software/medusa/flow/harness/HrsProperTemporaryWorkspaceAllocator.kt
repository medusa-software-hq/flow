package software.medusa.flow.harness

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.deleteRecursively
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName

class HrsProperTemporaryWorkspaceAllocator(
    private val coroutineScope: CoroutineScope,
    private val rootTemporaryDirectory: UfsMutableDirectory,
) : HrsTemporaryWorkspaceAllocator {
  companion object {
    private val workspacePrefix = UfsName.Literal("workspace-")

    suspend fun prepare(
        coroutineScope: CoroutineScope,
    ): HrsProperTemporaryWorkspaceAllocator {
      val tempDirectory = UfsNioDirectory.createTemporary(prefix = workspacePrefix)

      return HrsProperTemporaryWorkspaceAllocator(
          coroutineScope = coroutineScope,
          rootTemporaryDirectory = tempDirectory,
      )
    }
  }

  private var nextIndex = 0

  override suspend fun allocateTemporaryWorkspace(): HrsMutableTemporaryWorkspace {
    val workspaceDirectory =
        rootTemporaryDirectory.createDirectory(
            name = UfsName.Literal("workspace-${nextIndex++}"),
        )

    return object : HrsMutableTemporaryWorkspace {
      override val rootDirectory: UfsMutableDirectory
        get() = workspaceDirectory

      override fun close() {
        coroutineScope.launch { workspaceDirectory.deleteRecursively() }
      }
    }
  }
}
