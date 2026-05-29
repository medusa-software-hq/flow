package software.medusa.git.worktree

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory

@JvmInline
value class GitWorktree(
    /** The root directory of the Git worktree. */
    val rootDirectory: GitIncludedWorktreeDirectory,
) {
  companion object {
    suspend fun load(
        repoDirectory: ReadonlyCompatFsDirectory,
        globalFilter: GitWorktreeFilter = GitWorktreeFilter.Passive,
    ): GitWorktree =
        GitWorktree(
            with(GitIncludedWorktreeDirectory.GitignoreLocalFilterLoader) {
              GitIncludedWorktreeDirectory.include(
                  fsDirectory = repoDirectory,
                  baseFilter = globalFilter,
              )
            },
        )
  }
}
