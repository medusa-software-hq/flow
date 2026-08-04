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
      try {
        sourceRootDirectory.materializeIn(
            targetDirectory = allocatedWorkspace.rootDirectory,
        )
      } catch (e: Throwable) {
        // A cancelled session (or a mid-copy I/O failure) must not orphan the freshly-allocated
        // temp directory — the workspace has no other owner yet, so this is the only place that
        // can close it.
        allocatedWorkspace.close()
        throw e
      }

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
  try {
    templateDirectory.materializeIn(
        targetDirectory = allocatedWorkspace.rootDirectory,
    )
  } catch (e: Throwable) {
    // Same reasoning as the companion overload: nothing else owns this workspace yet, so a
    // cancelled session or a mid-copy failure must close it here or it leaks on disk.
    allocatedWorkspace.close()
    throw e
  }

  return allocatedWorkspace
}
