package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktree

@JvmInline
value class VedWorktree(
    val rootDirectory: VedExpandedDirectory,
) {
  companion object {
    suspend fun import(
        sourceWorktree: GitWorktree,
    ): VedWorktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory.load(
                    sourceDirectory = sourceWorktree.rootDirectory,
                    childEntityLoader = VedEntity.ImportingLoader,
                ),
        )
  }
}
