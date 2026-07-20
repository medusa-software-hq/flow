package software.medusa.flow.worker

import java.nio.file.Path
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory

/**
 * Clones a repo's default branch over HTTPS using a GitHub token, via `git` on `PATH` (no clone
 * support in `medusa.commons:git`).
 */
class WrkProcessGitCloner(
    private val tokenSupplierFactory: WrkGitHubTokenSupplierFactory,
) : WrkGitCloner {
  override suspend fun cloneDefaultBranch(
      repoFullName: String,
      targetDirectory: Path,
  ): GitWorktree {
    WrkGitProcess.run(
        workingDirectory = null,
        gitHubToken = tokenSupplierFactory.forRepo(repoFullName),
        "clone",
        "--depth",
        "1",
        "https://x-access-token@github.com/$repoFullName.git",
        targetDirectory.toString(),
    )

    return GitWorktree.load(
        repoDirectory = UfsNioDirectory(directoryPath = targetDirectory),
        globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
    )
  }
}
