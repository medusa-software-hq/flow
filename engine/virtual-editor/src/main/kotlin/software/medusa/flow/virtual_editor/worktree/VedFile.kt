package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFile
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

sealed class VedFile : VedEntity() {
  companion object {
    suspend fun import(
        sourceFile: GitWorktreeFile,
    ): VedFile {
      when (sourceFile.status) {
        GitWorktreeEntity.Status.included -> {
          val byteContent = sourceFile.asFilesystemEntity.read()

          val textContent =
              TxtFileContent.decode(
                  byteContent = byteContent,
              ) ?: return VedClosedFile

          return VedOpenedFile(
              content = textContent,
          )
        }

        else -> return VedClosedFile
      }
    }
  }

  abstract fun visitOpenedFile(
      filePath: UfsLiteralAbsolutePath,
  ): VedOpenedFile.Visited?
}
