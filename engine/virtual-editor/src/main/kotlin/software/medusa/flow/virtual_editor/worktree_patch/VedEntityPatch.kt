package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.mutation.UfsEntityMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity

sealed class VedEntityPatch {
  data class PatchApplicationResult<EntityT : VedEntity, MutationT : UfsEntityMutation>(
      val patchedEntity: EntityT,
      val entityMutation: MutationT,
  )

  abstract fun patchEntity(
      entity: VedEntity,
      timestamp: VedTimestamp,
  ): PatchApplicationResult<*, *>
}
