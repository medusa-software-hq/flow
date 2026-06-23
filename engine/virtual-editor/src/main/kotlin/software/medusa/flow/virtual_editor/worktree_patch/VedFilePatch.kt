package software.medusa.flow.virtual_editor.worktree_patch

import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

data class VedFilePatch(
    val txtPatch: TxtPatch,
) : VedEntityPatch() {
  typealias FilePatchApplicationResult = PatchApplicationResult<VedFile, UfsFileMutation>

  override fun apply(entity: VedEntity): FilePatchApplicationResult =
      when (entity) {
        is VedFile -> apply(entity)
        is VedDirectory -> error("Expected VedFile, got VedDirectory")
      }

  fun apply(file: VedFile): FilePatchApplicationResult =
      when (file) {
        is VedOpenedFile -> apply(file)
        else -> error("Cannot patch a closed file")
      }

  fun apply(file: VedOpenedFile): FilePatchApplicationResult {
    val newContent =
        TxtFileContent(
            content = file.content.content.applyPatch(patch = txtPatch),
        )

    return FilePatchApplicationResult(
        patchedEntity =
            VedOpenedFile(
                content = newContent,
            ),
        entityMutation =
            UfsFileMutation.Update(
                newContent = newContent.dump().encodeToByteString(),
            ),
    )
  }
}
