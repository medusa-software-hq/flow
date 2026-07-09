package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * The pipeline's major stage boundaries, reported once per stage (or once per attempt/round, for
 * the stages that repeat) via [HrsTaskCompleter.Observer.observePhase].
 *
 * This exists to close the "zero signal during minutes of setup" gap the earlier design left: steps
 * before the first AI call (manifest loading, workspace allocation, the initial health gate) had no
 * observer coverage at all. It is deliberately a coarse boundary marker — the existing
 * [HrsTaskCompleter.ScoutingObserver] / [HrsTaskCompleter.SolutionImplementationObserver] callbacks
 * remain the source of per-round/per-attempt detail.
 */
sealed class HrsPipelinePhase {
  /** Manifest loading and physical workspace allocation. */
  data object WorkspacePreparing : HrsPipelinePhase()

  /** The initial bootstrap/analyze/test gate, before any AI involvement. */
  data object HealthGate : HrsPipelinePhase()

  /**
   * The scouting conversation as a whole (individual rounds are reported via
   * [HrsTaskCompleter.ScoutingObserver]).
   */
  data object Scouting : HrsPipelinePhase()

  data object WorkspaceBriefing : HrsPipelinePhase()

  data object ImplementationPlanning : HrsPipelinePhase()

  /** The start of implementation attempt [attemptNumber] (1-indexed) of at most [maxAttempts]. */
  data class ImplementationAttempt(
      val attemptNumber: Int,
      val maxAttempts: Int,
  ) : HrsPipelinePhase()

  /** The post-patch health check for implementation attempt [attemptNumber]. */
  data class HealthCheck(
      val attemptNumber: Int,
      val maxAttempts: Int,
  ) : HrsPipelinePhase()
}

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

      /** The solution was still unhealthy after every implementation attempt was spent. */
      data class AttemptsExhausted(
          val attemptsMade: Int,
          val lastHealthStatus: ProjectHealthStatus.Unhealthy,
      ) : Failure()
    }
  }

  interface Observer {
    data object Noop : Observer {
      override fun observeScouting(): ScoutingObserver = ScoutingObserver.Noop

      override fun observeSolutionImplementation(): SolutionImplementationObserver =
          SolutionImplementationObserver.Noop

      override fun observeWorkspaceBriefing(): WorkspaceBriefingObserver =
          WorkspaceBriefingObserver.Noop

      override fun observeImplementationPlan(
          implementationPlan: HrsExpertAiSystem.ImplementationPlan,
      ) {}

      override fun observePhase(
          phase: HrsPipelinePhase,
      ) {}
    }

    fun observeScouting(): ScoutingObserver

    fun observeSolutionImplementation(): SolutionImplementationObserver

    fun observeWorkspaceBriefing(): WorkspaceBriefingObserver

    fun observeImplementationPlan(
        implementationPlan: HrsExpertAiSystem.ImplementationPlan,
    )

    /** Fired once at each pipeline stage boundary; see [HrsPipelinePhase]. */
    fun observePhase(
        phase: HrsPipelinePhase,
    )
  }

  interface ScoutingObserver {
    data object Noop : ScoutingObserver {
      override fun observeRound(
          roundNumber: Int,
          baseEditorWorktree: VedWorktree,
          scoutMessage: HrsFrontlineAiSystem.ScoutMessage,
      ) {}

      override fun observeRawResponse(
          response: OaiConfiguredClient.UnstructuredCompletionResponse,
      ) {}
    }

    /** [roundNumber] is 1-indexed. */
    fun observeRound(
        roundNumber: Int,
        baseEditorWorktree: VedWorktree,
        scoutMessage: HrsFrontlineAiSystem.ScoutMessage,
    )

    fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    )
  }

  interface SolutionImplementationObserver {
    data object Noop : SolutionImplementationObserver {
      override fun observeImplementation(
          attemptNumber: Int,
          patchMessage: HrsFrontlineAiSystem.PatchMessage,
      ) {}

      override fun observeHealthStatus(
          healthStatus: HrsFrontlineAiSystem.ProjectHealthStatus,
      ) {}

      override fun observeRawResponse(
          response: OaiConfiguredClient.UnstructuredCompletionResponse,
      ) {}
    }

    /** [attemptNumber] is 1-indexed. */
    fun observeImplementation(
        attemptNumber: Int,
        patchMessage: HrsFrontlineAiSystem.PatchMessage,
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
