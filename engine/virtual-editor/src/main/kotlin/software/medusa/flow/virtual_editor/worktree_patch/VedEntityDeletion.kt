package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile

/**
 * A patch that removes the entity at its name. Deleting an entity that does not exist is an error.
 */
data object VedEntityDeletion : VedEntityPatch() {
  override fun resolveInParent(
      existingEntity: VedEntity?,
      timestamp: VedTimestamp,
  ): ChildResolution =
      when (existingEntity) {
        null -> error("Cannot delete an entity that does not exist")

        is VedFile -> ChildResolution.Deleted(mutation = UfsFileMutation.Delete)

        is VedDirectory ->
            ChildResolution.Deleted(
                mutation =
                    UfsDirectoryMutation.Delete(mode = UfsDirectoryMutation.Delete.Mode.Recursive),
            )
      }

  override fun createEntity(
      timestamp: VedTimestamp,
  ): CreationResult = error("A deletion cannot create an entity")
}
