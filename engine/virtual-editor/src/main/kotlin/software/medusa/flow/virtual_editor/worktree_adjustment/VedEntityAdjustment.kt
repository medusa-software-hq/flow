package software.medusa.flow.virtual_editor.worktree_adjustment

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity

sealed class VedEntityAdjustment {
  @JvmInline
  value class AdjustmentResult<EntityT : VedEntity>(
      val adjustedEntity: EntityT,
  )

  abstract suspend fun adjustEntity(
      gitEntity: GitWorktreeEntity,
      editorEntity: VedEntity,
      timestamp: VedTimestamp,
  ): AdjustmentResult<*>
}
