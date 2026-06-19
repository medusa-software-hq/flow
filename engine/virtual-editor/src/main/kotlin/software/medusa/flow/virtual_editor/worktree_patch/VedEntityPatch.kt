package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.flow.virtual_editor.worktree.VedEntity

sealed class VedEntityPatch {
  abstract fun apply(entity: VedEntity): VedEntity
}
