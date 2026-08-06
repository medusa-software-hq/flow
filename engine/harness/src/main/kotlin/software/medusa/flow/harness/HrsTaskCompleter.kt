package software.medusa.flow.harness

import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind
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

/**
 * How an engine run is governed — surfaced once per run in the engine banner.
 *
 * Engine-agnostic on purpose: the classic engine is always manifest-driven ([Gated]); the claude
 * engine reports [ManifestLess] until its A4 health gate lands, then [Gated] when a manifest drives
 * an analyze/test gate. M3's leader can reuse the same two values.
 */
enum class HrsEngineRunMode {
  /** A manifest-driven analyze/test gate governs the run. */
  Gated,

  /** No manifest gate — the agent is trusted to self-verify (claude A3 default). */
  ManifestLess,
}

/**
 * The opening identity of an engine run, surfaced once for the UI banner (init → banner). Fields
 * are nullable where the underlying stream may omit them; the display layer degrades gracefully.
 */
data class HrsEngineBanner(
    /** Product-facing engine name, e.g. "Claude Agent" (never "Claude Code"). */
    val engineName: String,
    val cliVersion: String?,
    val model: String?,
    val runMode: HrsEngineRunMode,
)

/**
 * The terminal accounting of an engine run (dollar cost + usage), surfaced once (result → cost).
 */
data class HrsRunCost(
    val totalCostUsd: Double?,
    val numTurns: Int?,
    val durationMs: Long?,
)

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

    /**
     * A human-readable record of a single agent action: either a full assistant narrative (which
     * may be multi-line, up to the wire cap) or a short one-line tool action such as "edited
     * `x/y.kt`". Engine-agnostic; no-op by default so classic/scripted engines and existing
     * observers are unaffected. Volume is bounded by the reporting adapter, not here.
     */
    fun observeAgentAction(
        summary: String,
    ) {}

    /** The engine's opening banner (identity + run mode); fired once. No-op by default. */
    fun observeEngineBanner(
        banner: HrsEngineBanner,
    ) {}

    /** The run's terminal cost/usage; fired once. No-op by default. */
    fun observeRunCost(
        cost: HrsRunCost,
    ) {}

    /**
     * A low-severity engine-level warning that didn't fail the run — e.g. non-empty stderr on a
     * process that otherwise exited clean with a successful result. No-op by default.
     */
    fun observeEngineWarning(
        message: String,
    ) {}

    /**
     * A chunk summary was generated and stored (M3-07's leader-history compaction): [kind]
     * distinguishes a small-chunk close from a big-chunk close, [delegationRange] is the closed
     * chunk's span, and [summary] is exactly what got stored. Fires only on a *successful*
     * generation — a failed one degrades silently (the leader's rendering falls back to full/small
     * contents; the next close retries) and is not observed here. No-op by default.
     */
    fun observeCompaction(
        kind: HrsChunkSummaryKind,
        delegationRange: IntRange,
        summary: HrsChunkSummary,
    ) {}
  }

  interface ScoutingObserver {
    data object Noop : ScoutingObserver {
      override fun observeRound(
          roundNumber: Int,
          baseEditorWorktree: VedWorktree,
          scoutMessage: HrsFrontlineAiSystem.ScoutMessage,
      ) {}

      override fun observeRawResponse(
          responseText: String,
      ) {}
    }

    /** [roundNumber] is 1-indexed. */
    fun observeRound(
        roundNumber: Int,
        baseEditorWorktree: VedWorktree,
        scoutMessage: HrsFrontlineAiSystem.ScoutMessage,
    )

    fun observeRawResponse(
        responseText: String,
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
          responseText: String,
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
        responseText: String,
    )
  }

  interface WorkspaceBriefingObserver {
    data object Noop : WorkspaceBriefingObserver {
      override fun observeRawResponse(
          responseText: String,
      ) {}
    }

    fun observeRawResponse(
        responseText: String,
    )
  }

  suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: Observer,
  ): TaskCompletionResult
}
