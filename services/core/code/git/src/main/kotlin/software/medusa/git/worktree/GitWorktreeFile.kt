package software.medusa.git.worktree

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile

class GitWorktreeFile(
    private val fsFile: ReadonlyCompatFsFile,
    override val status: Status,
) : GitWorktreeEntity() {
  override val asFilteredFsEntity: ReadonlyCompatFsFile?
    get() = fsFile.filter(this.status)
}
