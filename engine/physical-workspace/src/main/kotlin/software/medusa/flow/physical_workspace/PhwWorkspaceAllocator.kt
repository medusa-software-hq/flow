package software.medusa.flow.physical_workspace

import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.materializeIn

interface PhwWorkspaceAllocator {
  companion object {
    suspend fun PhwWorkspaceAllocator.allocateWorkspace(
        sourceRootDirectory: UfsReadonlyDirectory,
    ): PhwWorkspace {
      val allocatedWorkspace = allocateWorkspace()

      // `materializeIn`, not `copyRecursivelyTo`: the workspace is a freshly-created empty temp
      // directory, so we want a clean mirror of the template, not an overlay merge. Crucially,
      // `materializeIn` propagates the executable bit whereas `copyRecursivelyTo` drops it — using
      // the overlay helper here silently stripped 100755 from files like `gradlew`, which then
      // showed up as spurious mode-only diffs and junk PRs on no-op runs.
      sourceRootDirectory.materializeIn(
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

  // See the companion overload above: a clean mirror into the empty workspace, preserving exec
  // bits.
  templateDirectory.materializeIn(
      targetDirectory = allocatedWorkspace.rootDirectory,
  )

  return allocatedWorkspace
}
