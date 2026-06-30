package software.medusa.flow.virtual_editor.worktree_adjustment

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree

data class VedWorktreeAdjustment(
    val rootDirectoryAdjustment: VedDirectoryAdjustment.Dive,
) {
  data class AdjustmentResult(
      val adjustedWorktree: VedWorktree,
  )

  suspend fun adjust(
      gitWorktree: GitWorktree,
      editorWorktree: VedWorktree,
  ): AdjustmentResult {
    val rootDirectoryResult =
        rootDirectoryAdjustment.adjustDirectory(
            gitDirectory = gitWorktree.rootDirectory,
            editorDirectory = editorWorktree.rootDirectory,
        )

    return AdjustmentResult(
        adjustedWorktree =
            VedWorktree(
                rootDirectory = rootDirectoryResult.adjustedEntity,
            ),
    )
  }
}
