package software.medusa.code_agent.scouting

import software.medusa.code_agent.CodeTaskDescription
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace

class ProperCodeWorktreeScout : CodeWorktreeScout {
  override suspend fun scoutWorktree(
      preScoutedVirtualWorkspace: CodeVirtualWorkspace,
      taskDescription: CodeTaskDescription,
  ): CodeVirtualWorkspace {
    TODO("Not yet implemented")
  }
}
