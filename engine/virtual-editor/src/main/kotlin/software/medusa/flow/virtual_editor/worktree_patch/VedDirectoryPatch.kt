package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedFile

data class VedDirectoryPatch(
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch>,
) : VedEntityPatch() {
  typealias DirectoryPatchApplicationResult =
      PatchApplicationResult<VedExpandedDirectory, UfsDirectoryMutation>

  override fun patchEntity(
      entity: VedEntity,
      timestamp: VedTimestamp,
  ): DirectoryPatchApplicationResult =
      when (entity) {
        is VedDirectory ->
            patchDirectory(
                directory = entity,
                timestamp = timestamp,
            )

        is VedFile -> error("Expected VedDirectory, got VedFile")
      }

  fun patchDirectory(
      directory: VedDirectory,
      timestamp: VedTimestamp,
  ): DirectoryPatchApplicationResult =
      when (directory) {
        is VedExpandedDirectory ->
            patchExpandedDirectory(
                directory = directory,
                timestamp = timestamp,
            )

        else -> error("Cannot patch a collapsed directory")
      }

  fun patchExpandedDirectory(
      directory: VedExpandedDirectory,
      timestamp: VedTimestamp,
  ): DirectoryPatchApplicationResult {
    // The result of applying the patch to each child that has one (computed once, reused below).
    val childResultByName =
        directory.labeledEntityByName
            .mapNotNull { (name, labeledEntity) ->
              val childPatch = childPatchByName[name] ?: return@mapNotNull null

              name to
                  childPatch.patchEntity(
                      entity = labeledEntity.entity,
                      timestamp = timestamp,
                  )
            }
            .toMap()

    // Children without a patch are carried over untouched and contribute no mutation.
    val patchedLabeledEntityByName =
        directory.labeledEntityByName.mapValues { (name, labeledEntity) ->
          val childResult = childResultByName[name] ?: return@mapValues labeledEntity
          labeledEntity.copy(entity = childResult.patchedEntity)
        }

    val operationByName = childResultByName.mapValues { (_, childResult) ->
      UfsDirectoryMutation.Dive.Operation.Mutate(mutation = childResult.entityMutation)
    }

    return DirectoryPatchApplicationResult(
        patchedEntity = directory.copy(labeledEntityByName = patchedLabeledEntityByName),
        entityMutation = UfsDirectoryMutation.Dive(operationByName = operationByName),
    )
  }
}
