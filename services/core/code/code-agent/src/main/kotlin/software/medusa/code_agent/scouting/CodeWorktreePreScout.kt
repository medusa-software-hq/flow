package software.medusa.code_agent.scouting

import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace
import software.medusa.git.worktree.GitWorktree

interface CodeWorktreePreScout {
  /**
   * Pre-scouts the given [gitWorktree], building an initial [CodeVirtualWorkspace]. The virtual
   * workspace is constructed heuristically and is meant to be lightweight yet informative.
   */
  suspend fun preScoutWorktree(
      gitWorktree: GitWorktree,
  ): CodeVirtualWorkspace
}
