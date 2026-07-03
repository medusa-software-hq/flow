package software.medusa.flow.virtual_editor.worktree_patch

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.impl.immutable.UfsImmutableFile
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

data class VedFilePatch(
    val txtPatch: TxtPatch,
) : VedEntityPatch() {
  typealias FilePatchApplicationResult = PatchApplicationResult<VedFile, UfsFileMutation>

  // An existing opened file is rewritten; an absent name is created; anything else (a closed file
  // or a directory) is a mismatch.
  override fun resolveInParent(
      existingEntity: VedEntity?,
      timestamp: VedTimestamp,
  ): ChildResolution =
      when (existingEntity) {
        is VedOpenedFile -> {
          val result = patchOpenedFile(openedFile = existingEntity, timestamp = timestamp)
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

        else -> error("Cannot apply a file patch to a ${existingEntity::class.simpleName}")
      }

  fun patchFile(
      file: VedFile,
      timestamp: VedTimestamp,
  ): FilePatchApplicationResult =
      when (file) {
        is VedOpenedFile ->
            patchOpenedFile(
                openedFile = file,
                timestamp = timestamp,
            )

        else -> error("Cannot patch a closed file")
      }

  fun patchOpenedFile(
      openedFile: VedOpenedFile,
      timestamp: VedTimestamp,
  ): FilePatchApplicationResult {
    val newContent =
        openedFile.currentContent.applyPatch(
            patch = txtPatch,
        )

    return FilePatchApplicationResult(
        patchedEntity =
            openedFile.update(
                newContent = newContent,
                timestamp = timestamp,
            ),
        entityMutation =
            UfsFileMutation.Update(
                newContent = newContent.dump().encodeToByteString(),
            ),
    )
  }

  // Creating a file means applying the patch to empty content, so a whole-file "replacement" of a
  // zero-length file yields exactly the new content. The freshly created file is opened.
  override fun createEntity(
      timestamp: VedTimestamp,
  ): CreationResult {
    val newContent = TxtFileContent(content = TxtBlock.Empty).applyPatch(patch = txtPatch)

    return CreationResult(
        createdEntity = VedOpenedFile.of(content = newContent, timestamp = timestamp),
        templateEntity = UfsImmutableFile(content = newContent.dump().encodeToByteString()),
    )
  }
}
