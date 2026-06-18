package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

internal data object VedCollapsedDirectory : VedDirectory() {
  override fun visitOpenedFiles(
      directoryPath: UfsLiteralAbsolutePath,
  ): Sequence<VedOpenedFile.Visited> = emptySequence()
}
