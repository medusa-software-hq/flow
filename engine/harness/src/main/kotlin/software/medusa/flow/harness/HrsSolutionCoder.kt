package software.medusa.flow.harness

import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

interface HrsSolutionCoder {
  suspend fun codeSolution(
      editorWorktree: VedWorktree,
      taskDescription: HrsTaskDescription,
  ): VedWorktreePatch
}
