package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree

interface HrsTaskCompleter {
  suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
  ): HrsReadonlyTemporaryWorkspace
}
