package software.medusa.git.worktree

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.git.worktree.GitWorktreeEntity.Status
import software.medusa.git.worktree.GitWorktreeFilter.Classification

class GitWorktreeFile(
    private val fsFile: ReadonlyCompatFsFile,
    override val status: Status,
) : GitWorktreeEntity, ReadonlyCompatFsFile by fsFile {
  override val asFsEntity: ReadonlyCompatFsFile
    get() = fsFile

  override val asFilteredFsEntity: ReadonlyCompatFsFile?
    get() =
        when (status) {
          is Status.Considered -> this.takeIf { status.classification == Classification.Include }
          Status.NonConsidered -> null
        }
}
