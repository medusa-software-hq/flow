package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

interface HrsTaskCompleter {
  enum class JointOperationPhase {
    ProjectBootstrapping,
    InitialProjectAnalysis,
    InitialProjectTesting,
    FinalProjectAnalysis,
    FinalProjectTesting,
  }

  sealed class TaskCompletionResult {
    data class Success(
        val temporaryWorkspace: HrsReadonlyTemporaryWorkspace,
    ) : TaskCompletionResult()

    sealed class Failure : TaskCompletionResult() {
      data class JointOperation(
          val phase: JointOperationPhase,
          val operationFailure: JointResult.Failure,
      ) : Failure()
    }
  }

  interface Observer {
    fun observeScouting(): ScoutingObserver

    fun observeImplementedSolution(
        solutionPatch: VedWorktreePatch,
    )
  }

  interface ScoutingObserver {
    fun observeRound(
        baseEditorWorktree: VedWorktree,
        scoutingResult: HrsFrontlineAiSystem.ScoutingResult,
    )
  }

  suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult
}
