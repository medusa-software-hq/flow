package software.medusa.code_agent

import software.medusa.code_agent.scouting.CodeWorktreePreScout
import software.medusa.code_agent.scouting.CodeWorktreeScout
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.git.worktree.GitWorktree

class ProperCodeTaskCompleter(
    private val worktreePreScout: CodeWorktreePreScout,
    private val worktreeScout: CodeWorktreeScout,
) : CodeTaskCompleter {
  override suspend fun completeTask(
      codeDirectory: ReadonlyCompatFsDirectory,
      taskDescription: CodeTaskDescription,
  ) {
    val gitWorktree = GitWorktree.load(repoDirectory = codeDirectory)

    val preScoutedVirtualWorkspace =
        worktreePreScout.preScoutWorktree(
            gitWorktree = gitWorktree,
        )

    val scoutedVirtualWorkspace =
        worktreeScout.scoutWorktree(
            preScoutedVirtualWorkspace = preScoutedVirtualWorkspace,
            taskDescription = taskDescription,
        )

    TODO("Not yet implemented")
  }
}
