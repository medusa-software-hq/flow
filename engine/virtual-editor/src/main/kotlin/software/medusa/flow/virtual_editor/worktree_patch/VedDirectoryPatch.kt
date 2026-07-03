package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.unix.filesystem.impl.immutable.UfsImmutableDirectory
import software.medusa.commons.unix.filesystem.mutation.UfsDirectoryMutation
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory

data class VedDirectoryPatch(
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch>,
) : VedEntityPatch() {
  typealias DirectoryPatchApplicationResult =
      PatchApplicationResult<VedExpandedDirectory, UfsDirectoryMutation>

  // An existing expanded directory is patched in place; an absent name is created; anything else (a
  // collapsed directory or a file) is a mismatch.
  override fun resolveInParent(
      existingEntity: VedEntity?,
      timestamp: VedTimestamp,
  ): ChildResolution =
      when (existingEntity) {
        is VedExpandedDirectory -> {
          val result = patchExpandedDirectory(directory = existingEntity, timestamp = timestamp)
          ChildResolution.Mutated(
              patchedEntity = result.patchedEntity,
              mutation = result.entityMutation,
          )
        }

        null -> {
          val creation = createEntity(timestamp = timestamp)
          ChildResolution.Created(
              createdEntity = creation.createdEntity,
              templateEntity = creation.templateEntity,
          )
        }

        else -> error("Cannot apply a directory patch to a ${existingEntity::class.simpleName}")
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
    val resolutionByName = childPatchByName.mapValues { (name, childPatch) ->
      childPatch.resolveInParent(
          existingEntity = directory.labeledEntityByName[name]?.entity,
          timestamp = timestamp,
      )
    }

    val patchedLabeledEntityByName = buildMap {
      // Existing children: keep untouched, replace when mutated, drop when deleted.
      directory.labeledEntityByName.forEach { (name, labeledEntity) ->
        when (val resolution = resolutionByName[name]) {
          null -> put(name, labeledEntity)

          is ChildResolution.Mutated ->
              put(name, labeledEntity.copy(entity = resolution.patchedEntity))

          is ChildResolution.Deleted -> Unit

          is ChildResolution.Created ->
              error("A patch created `${name.content}`, which already exists")
        }
      }

      // New children: add those created at a name that had no existing entity.
      resolutionByName.forEach { (name, resolution) ->
        if (resolution is ChildResolution.Created && name !in directory.labeledEntityByName) {
          put(
              name,
              VedExpandedDirectory.LabeledEntity(
                  status = GitWorktreeEntity.Status.included,
                  entity = resolution.createdEntity,
              ),
          )
        }
      }
    }

    val operationByName = resolutionByName.mapValues { (_, resolution) ->
      when (resolution) {
        is ChildResolution.Mutated ->
            UfsDirectoryMutation.Dive.Operation.Mutate(mutation = resolution.mutation)

        is ChildResolution.Created ->
            UfsDirectoryMutation.Dive.Operation.Create(templateEntity = resolution.templateEntity)

        is ChildResolution.Deleted ->
            UfsDirectoryMutation.Dive.Operation.Mutate(mutation = resolution.mutation)
      }
    }

    return DirectoryPatchApplicationResult(
        patchedEntity = directory.copy(labeledEntityByName = patchedLabeledEntityByName),
        entityMutation = UfsDirectoryMutation.Dive(operationByName = operationByName),
    )
  }

  // Creating a directory creates each of its patched children in turn — so a patch aimed at a file
  // several not-yet-existing directories deep materializes the whole chain.
  override fun createEntity(
      timestamp: VedTimestamp,
  ): CreationResult {
    val childCreationByName = childPatchByName.mapValues { (_, childPatch) ->
      childPatch.createEntity(timestamp = timestamp)
    }

    return CreationResult(
        createdEntity =
            VedExpandedDirectory(
                labeledEntityByName =
                    childCreationByName.mapValues { (_, creationResult) ->
                      VedExpandedDirectory.LabeledEntity(
                          status = GitWorktreeEntity.Status.included,
                          entity = creationResult.createdEntity,
                      )
                    },
            ),
        templateEntity =
            UfsImmutableDirectory(
                childEntityByName =
                    childCreationByName.mapValues { (_, creationResult) ->
                      creationResult.templateEntity
                    },
            ),
    )
  }
}
