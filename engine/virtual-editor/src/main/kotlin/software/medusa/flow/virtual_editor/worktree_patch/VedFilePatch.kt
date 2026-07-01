package software.medusa.flow.virtual_editor.worktree_patch

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

data class VedFilePatch(
    val txtPatch: TxtPatch,
) : VedEntityPatch() {
  typealias FilePatchApplicationResult = PatchApplicationResult<VedFile, UfsFileMutation>

  override fun patchEntity(
      entity: VedEntity,
      timestamp: VedTimestamp,
  ): FilePatchApplicationResult =
      when (entity) {
        is VedFile ->
            patchFile(
                file = entity,
                timestamp = timestamp,
            )

        is VedDirectory -> error("Expected VedFile, got VedDirectory")
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
}
