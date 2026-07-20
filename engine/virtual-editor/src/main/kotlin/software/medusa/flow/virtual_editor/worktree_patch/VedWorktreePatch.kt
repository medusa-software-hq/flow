package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree

data class VedWorktreePatch(
    val rootDirectoryPatch: VedDirectoryPatch,
) {
  data class PatchApplicationResult(
      val patchedWorktree: VedWorktree,
      val rootDirectoryMutation: UfsDirectoryMutation,
  )

  fun patchWorktree(
      worktree: VedWorktree,
      timestamp: VedTimestamp,
  ): PatchApplicationResult {
    val rootDirectoryResult =
        rootDirectoryPatch.patchExpandedDirectory(
            directory = worktree.rootDirectory,
            timestamp = timestamp,
        )

    return PatchApplicationResult(
        patchedWorktree =
            VedWorktree(
                rootDirectory = rootDirectoryResult.patchedEntity,
            ),
        rootDirectoryMutation = rootDirectoryResult.entityMutation,
    )
  }
}
