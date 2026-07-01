package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.worktree.VedWorktree

interface HrsTaskCompleter {
  enum class JointOperationPhase {
    ProjectBootstrapping,
    InitialProjectAnalysis,
    InitialProjectTesting,
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

    fun observeSolutionImplementation(): SolutionImplementationObserver

    fun observeWorkspaceBriefing(): WorkspaceBriefingObserver

    fun observeImplementationPlan(
        implementationPlan: HrsExpertAiSystem.ImplementationPlan,
    )
  }

  interface ScoutingObserver {
    fun observeRound(
        baseEditorWorktree: VedWorktree,
        scoutCommand: HrsFrontlineAiSystem.ScoutCommand,
    )

    fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    )
  }

  interface SolutionImplementationObserver {
    data object Noop : SolutionImplementationObserver {
      override fun observeImplementation(
          patchCommand: HrsFrontlineAiSystem.PatchCommand,
      ) {}

      override fun observeHealthStatus(
          healthStatus: HrsFrontlineAiSystem.ProjectHealthStatus,
      ) {}

      override fun observeRawResponse(
          response: OaiConfiguredClient.UnstructuredCompletionResponse,
      ) {}
    }

    fun observeImplementation(
        patchCommand: HrsFrontlineAiSystem.PatchCommand,
    )

    fun observeHealthStatus(
        healthStatus: HrsFrontlineAiSystem.ProjectHealthStatus,
    )

    fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    )
  }

  interface WorkspaceBriefingObserver {
    data object Noop : WorkspaceBriefingObserver {
      override fun observeRawResponse(
          response: OaiConfiguredClient.UnstructuredCompletionResponse,
      ) {}
    }

    fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    )
  }

  suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult
}
