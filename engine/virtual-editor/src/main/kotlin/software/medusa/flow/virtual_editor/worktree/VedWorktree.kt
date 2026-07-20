package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatWorktree

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

  fun visitOpenedFiles(): Sequence<VedOpenedFile.Visited> =
      rootDirectory.visitOpenedFiles(
          directoryPath = UfsAbsolutePath.Root,
      )

  fun flatten(): VedFlatWorktree =
      VedFlatWorktree(
          openedFiles =
              visitOpenedFiles().flatMap { it.flatten() }.sortedBy { it.sortKey }.toList(),
      )
}
