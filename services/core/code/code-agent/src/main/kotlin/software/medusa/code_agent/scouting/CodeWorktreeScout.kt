package software.medusa.code_agent.scouting

import software.medusa.code_agent.CodeTaskDescription
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace

interface CodeWorktreeScout {
  /**
   * Scouts the given [preScoutedVirtualWorkspace], opening files and expanding directories related
   * to the given [taskDescription] and collapsing directories that are unrelated.
   */
  suspend fun scoutWorktree(
      preScoutedVirtualWorkspace: CodeVirtualWorkspace,
      taskDescription: CodeTaskDescription,
  ): CodeVirtualWorkspace
}
