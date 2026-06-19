package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.flow.virtual_editor.worktree.VedWorktree

data class VedWorktreePatch(
    val rootDirectoryPatch: VedDirectoryPatch,
) {
  fun apply(worktree: VedWorktree): VedWorktree =
      VedWorktree(
          rootDirectory = rootDirectoryPatch.apply(worktree.rootDirectory),
      )
}
