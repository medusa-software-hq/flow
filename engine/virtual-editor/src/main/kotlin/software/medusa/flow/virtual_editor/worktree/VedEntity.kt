package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeDirectory
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFile

sealed class VedEntity {
  interface Loader {
    suspend fun load(
        sourceEntity: GitWorktreeEntity,
    ): VedEntity
  }

  data object ImportingLoader : Loader {
    override suspend fun load(
        sourceEntity: GitWorktreeEntity,
    ): VedEntity =
        when (sourceEntity) {
          is GitWorktreeDirectory -> VedDirectory.import(sourceDirectory = sourceEntity)
          is GitWorktreeFile -> VedClosedFile
        }
  }
}
