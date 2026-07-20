package software.medusa.flow.physical_workspace

import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.copyRecursivelyTo

interface PhwWorkspaceAllocator {
  companion object {
    suspend fun PhwWorkspaceAllocator.allocateWorkspace(
        sourceRootDirectory: UfsReadonlyDirectory,
    ): PhwWorkspace {
      val allocatedWorkspace = allocateWorkspace()

      sourceRootDirectory.copyRecursivelyTo(
          targetDirectory = allocatedWorkspace.rootDirectory,
      )

      return allocatedWorkspace
    }
  }

  suspend fun allocateWorkspace(): PhwWorkspace
}

suspend fun PhwWorkspaceAllocator.allocateWorkspace(
    templateDirectory: UfsReadonlyDirectory,
): PhwWorkspace {
  val allocatedWorkspace = allocateWorkspace()

  templateDirectory.copyRecursivelyTo(
      targetDirectory = allocatedWorkspace.rootDirectory,
  )

  return allocatedWorkspace
}
