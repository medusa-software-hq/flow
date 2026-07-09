package software.medusa.flow.worker

import java.nio.file.Path
import software.medusa.commons.git.worktree.GitWorktree

/** Clones a repo's default branch into [targetDirectory], returning it as a [GitWorktree]. */
fun interface WrkGitCloner {
  suspend fun cloneDefaultBranch(
      repoFullName: String,
      targetDirectory: Path,
  ): GitWorktree
}
