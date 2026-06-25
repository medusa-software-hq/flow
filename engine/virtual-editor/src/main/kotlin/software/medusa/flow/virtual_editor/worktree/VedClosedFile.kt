package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

data object VedClosedFile : VedFile() {
  override fun visitOpenedFile(
      filePath: UfsLiteralAbsolutePath,
  ): VedOpenedFile.Visited? = null
}
