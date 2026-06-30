package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

sealed class VedFile : VedEntity() {
  abstract fun visitOpenedFile(
      filePath: UfsLiteralAbsolutePath,
  ): VedOpenedFile.Visited?
}
