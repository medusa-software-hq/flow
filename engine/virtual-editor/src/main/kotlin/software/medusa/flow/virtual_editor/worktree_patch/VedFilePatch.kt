package software.medusa.flow.virtual_editor.worktree_patch

import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtPatch
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

data class VedFilePatch(
    val txtPatch: TxtPatch,
) : VedEntityPatch() {
  override fun apply(entity: VedEntity): VedEntity =
      when (entity) {
        is VedFile -> apply(entity)
        is VedDirectory -> error("Expected VedFile, got VedDirectory")
      }

  fun apply(file: VedFile): VedFile =
      when (file) {
        is VedOpenedFile -> apply(file)
        else -> error("Cannot patch a closed file")
      }

  fun apply(file: VedOpenedFile): VedOpenedFile =
      VedOpenedFile(
          content = TxtFileContent(content = file.content.content.applyPatch(patch = txtPatch)),
      )
}
