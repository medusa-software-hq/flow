package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.unix.filesystem.UfsReadonlyEntity
import software.medusa.commons.unix.filesystem.mutation.UfsEntityMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity

sealed class VedEntityPatch {
  data class PatchApplicationResult<EntityT : VedEntity, MutationT : UfsEntityMutation>(
      val patchedEntity: EntityT,
      val entityMutation: MutationT,
  )

  /**
   * The outcome of resolving a patch against the entity currently at its name in a directory — see
   * [resolveInParent]. It tells the containing directory both what to do to its (virtual) child map
   * and which filesystem operation to emit.
   */
  sealed class ChildResolution {
    /**
     * The child stays, replaced by [patchedEntity]; [mutation] is applied to the existing child.
     */
    data class Mutated(
        val patchedEntity: VedEntity,
        val mutation: UfsEntityMutation,
    ) : ChildResolution()

    /** A new child [createdEntity] is added; [templateEntity] materializes it in the filesystem. */
    data class Created(
        val createdEntity: VedEntity,
        val templateEntity: UfsReadonlyEntity,
    ) : ChildResolution()

    /** The child is removed; [mutation] deletes it from the filesystem. */
    data class Deleted(
        val mutation: UfsEntityMutation,
    ) : ChildResolution()
  }

  /** The [createdEntity]/[templateEntity] a patch produces when it creates a brand-new entity. */
  data class CreationResult(
      val createdEntity: VedEntity,
      val templateEntity: UfsReadonlyEntity,
  )

  /**
   * Resolves this patch against [existingEntity] — the entity currently at its name, or `null` when
   * none exists — into the [ChildResolution] the containing directory applies.
   */
  abstract fun resolveInParent(
      existingEntity: VedEntity?,
      timestamp: VedTimestamp,
  ): ChildResolution

  /** Creates the entity this patch describes, for a name that does not yet exist in its parent. */
  abstract fun createEntity(
      timestamp: VedTimestamp,
  ): CreationResult
}
