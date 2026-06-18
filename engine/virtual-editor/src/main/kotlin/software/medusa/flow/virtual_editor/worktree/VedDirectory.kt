package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeDirectory
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

sealed class VedDirectory : VedEntity() {
  companion object {
    suspend fun import(
        sourceDirectory: GitWorktreeDirectory,
    ): VedDirectory =
        when (sourceDirectory.status) {
          GitWorktreeEntity.Status.included ->
              VedExpandedDirectory.load(
                  sourceDirectory = sourceDirectory,
                  childEntityLoader = ImportingLoader,
              )

          else -> VedCollapsedDirectory
        }
  }

  abstract fun visitOpenedFiles(
      directoryPath: UfsLiteralAbsolutePath,
  ): Sequence<VedOpenedFile.Visited>
}
