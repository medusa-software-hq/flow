package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult

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

  suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
  ): TaskCompletionResult
}
