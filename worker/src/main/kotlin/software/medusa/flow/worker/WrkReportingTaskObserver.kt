package software.medusa.flow.worker

import kotlinx.coroutines.runBlocking
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.virtual_editor.worktree.VedWorktree

private fun phaseToEvent(
    phase: HrsPipelinePhase,
): Pair<SessionEventKind, String> =
    when (phase) {
      HrsPipelinePhase.WorkspacePreparing ->
          SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING to "Preparing workspace"

      HrsPipelinePhase.HealthGate ->
          SessionEventKind.SESSION_EVENT_KIND_HEALTH_GATE to "Running initial health gate"

      HrsPipelinePhase.Scouting ->
          SessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND to "Scouting the codebase"

      HrsPipelinePhase.WorkspaceBriefing ->
          SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_BRIEFING to "Preparing workspace briefing"

      HrsPipelinePhase.ImplementationPlanning ->
          SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING to
              "Planning the implementation"

      is HrsPipelinePhase.ImplementationAttempt ->
          SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT to
              "Implementation attempt ${phase.attemptNumber} of ${phase.maxAttempts}"

      is HrsPipelinePhase.HealthCheck ->
          SessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK to
              "Health check for attempt ${phase.attemptNumber}"
    }

/** "Powered by Claude" branding line + CLI/model/mode, as short Markdown. */
private fun formatBanner(
    banner: HrsEngineBanner,
): String = buildString {
  append("**Powered by ${banner.engineName}**")
  banner.cliVersion?.let { append(" · CLI $it") }
  banner.model?.let { append(" · model `$it`") }
  append(" · ")
  append(
      when (banner.runMode) {
        HrsEngineRunMode.Gated -> "gated mode"
        HrsEngineRunMode.ManifestLess -> "manifest-less mode"
      },
  )
}

/** Terminal cost/usage as short Markdown, e.g. "**$0.0100** · 2 turns · 1.2s". */
private fun formatRunCost(
    cost: HrsRunCost,
): String = buildString {
  append(cost.totalCostUsd?.let { "**$${"%.4f".format(it)}**" } ?: "**cost unknown**")
  cost.numTurns?.let { append(" · $it ${if (it == 1) "turn" else "turns"}") }
  cost.durationMs?.let { append(" · ${"%.1f".format(it / 1000.0)}s") }
}

/**
 * Maps [HrsTaskCompleter.Observer] callbacks to `AppendSessionEvent` calls, per
 * design/04-observability-and-github-layering.md: only short Markdown summaries cross the wire. Raw
 * model responses ([HrsTaskCompleter.*Observer.observeRawResponse]) never do -- there's nothing to
 * forward them to in M1, so they're simply dropped here.
 *
 * Observer callbacks are synchronous (the engine invokes them mid-pipeline, not as suspend
 * functions), so event delivery is a blocking RPC on the calling thread. A control-plane hiccup is
 * logged and swallowed -- it must never abort the actual engine run.
 */
class WrkReportingTaskObserver(
    private val sessionId: String,
    private val apiClient: WrkApiClient,
    private val log: (String) -> Unit,
    private val agentActionCoalescer: WrkAgentActionCoalescer = WrkAgentActionCoalescer(),
) : HrsTaskCompleter.Observer {
  private fun sendEvent(
      kind: SessionEventKind,
      message: String,
      costUsd: Double? = null,
  ) {
    try {
      runBlocking {
        apiClient.appendSessionEvent(
            sessionId = sessionId,
            kind = kind,
            message = message,
            costUsd = costUsd,
        )
      }
    } catch (e: Exception) {
      log("Session $sessionId: failed to report a progress event ($e)")
    }
  }

  override fun observeAgentAction(
      summary: String,
  ) {
    // Coalesce the (potentially very chatty) tool/narrative stream to a bounded event count.
    when (val decision = agentActionCoalescer.offer(summary)) {
      is WrkAgentActionCoalescer.Decision.Emit ->
          sendEvent(SessionEventKind.SESSION_EVENT_KIND_AGENT_ACTION, decision.message)
      WrkAgentActionCoalescer.Decision.Drop -> Unit
    }
  }

  override fun observeEngineBanner(
      banner: HrsEngineBanner,
  ) {
    sendEvent(SessionEventKind.SESSION_EVENT_KIND_ENGINE_BANNER, formatBanner(banner))
  }

  override fun observeRunCost(
      cost: HrsRunCost,
  ) {
    sendEvent(
        kind = SessionEventKind.SESSION_EVENT_KIND_RUN_COST,
        message = formatRunCost(cost),
        costUsd = cost.totalCostUsd,
    )
  }

  override fun observePhase(
      phase: HrsPipelinePhase,
  ) {
    val (kind, message) = phaseToEvent(phase)
    sendEvent(kind, message)
  }

  override fun observeImplementationPlan(
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
  ) {
    sendEvent(SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING, implementationPlan.body)
  }

  override fun observeScouting(): HrsTaskCompleter.ScoutingObserver = ScoutingObserverImpl()

  override fun observeSolutionImplementation(): HrsTaskCompleter.SolutionImplementationObserver =
      SolutionImplementationObserverImpl()

  override fun observeWorkspaceBriefing(): HrsTaskCompleter.WorkspaceBriefingObserver =
      HrsTaskCompleter.WorkspaceBriefingObserver.Noop

  private inner class ScoutingObserverImpl : HrsTaskCompleter.ScoutingObserver {
    override fun observeRound(
        roundNumber: Int,
        baseEditorWorktree: VedWorktree,
        scoutMessage: ScoutMessage,
    ) {
      sendEvent(
          SessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND,
          "Round $roundNumber:\n\n${scoutMessage.body}",
      )
    }

    override fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    ) = Unit
  }

  private inner class SolutionImplementationObserverImpl :
      HrsTaskCompleter.SolutionImplementationObserver {
    override fun observeImplementation(
        attemptNumber: Int,
        patchMessage: PatchMessage,
    ) = Unit

    override fun observeHealthStatus(
        healthStatus: ProjectHealthStatus,
    ) {
      val message =
          when (healthStatus) {
            ProjectHealthStatus.Healthy -> "Health checks passed."
            is ProjectHealthStatus.Unhealthy -> healthStatus.failureReport.toMarkdown()
          }

      sendEvent(SessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK, message)
    }

    override fun observeRawResponse(
        response: OaiConfiguredClient.UnstructuredCompletionResponse,
    ) = Unit
  }
}
