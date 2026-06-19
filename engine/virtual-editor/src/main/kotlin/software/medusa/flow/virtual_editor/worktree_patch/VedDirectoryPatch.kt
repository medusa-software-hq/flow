package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedFile

data class VedDirectoryPatch(
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch>,
) : VedEntityPatch() {
  override fun apply(entity: VedEntity): VedEntity =
      when (entity) {
        is VedDirectory -> apply(entity)
        is VedFile -> error("Expected VedDirectory, got VedFile")
      }

  fun apply(directory: VedDirectory): VedDirectory =
      when (directory) {
        is VedExpandedDirectory -> apply(directory)
        else -> error("Cannot patch a collapsed directory")
      }

  fun apply(directory: VedExpandedDirectory): VedExpandedDirectory =
      directory.copy(
          labeledEntityByName =
              directory.labeledEntityByName.mapValues { (name, labeledEntity) ->
                val childPatch = childPatchByName[name] ?: return@mapValues labeledEntity
                labeledEntity.copy(entity = childPatch.apply(labeledEntity.entity))
              },
      )
}
