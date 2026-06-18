package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

data class VedOpenedFile(
    val content: TxtFileContent,
) : VedFile() {
  data class Visited(
      val filePath: UfsLiteralAbsolutePath,
      val openedFile: VedOpenedFile,
  )

  override fun visitOpenedFile(
      filePath: UfsLiteralAbsolutePath,
  ): Visited =
      Visited(
          filePath = filePath,
          openedFile = this,
      )
}
