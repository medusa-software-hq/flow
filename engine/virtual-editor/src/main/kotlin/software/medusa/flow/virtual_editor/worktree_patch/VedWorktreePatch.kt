package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedWorktree

data class VedWorktreePatch(
    val rootDirectoryPatch: VedDirectoryPatch,
) {
  data class PatchApplicationResult(
      val patchedWorktree: VedWorktree,
      val rootDirectoryMutation: UfsDirectoryMutation,
  )

  fun apply(worktree: VedWorktree): PatchApplicationResult {
    val rootDirectoryResult = rootDirectoryPatch.apply(worktree.rootDirectory)

    return PatchApplicationResult(
        patchedWorktree =
            VedWorktree(rootDirectory = rootDirectoryResult.patchedEntity as VedExpandedDirectory),
        rootDirectoryMutation = rootDirectoryResult.entityMutation,
    )
  }
}
