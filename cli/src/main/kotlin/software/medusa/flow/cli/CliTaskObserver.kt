package software.medusa.flow.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.worker.toMarkdown

/**
 * Narrates a running [HrsTaskCompleter] straight to the terminal, colored by severity/outcome —
 * phase boundaries in bold, delegation reports and gate results colored by their verdict
 * ([HrsDelegationOutcome] / [ProjectHealthStatus]). This is the one [HrsTaskCompleter.Observer]
 * every engine can share: the classic and Claude Agent engines only ever drive the phase/banner/
 * cost/agent-action/health-check hooks (see [HrsTaskCompleter.SolutionImplementationObserver]), the
 * leader/assistant engine additionally drives the delegation-shaped hooks added for it
 * (`observeDelegationStarted`/`observeDelegationReport`/`observeGateResult`/`observeCompaction`) —
 * every hook this class doesn't render for a given engine simply never fires for it.
 *
 * [debug] gates the chatty, low-signal-to-noise lines: per-round raw leader/assistant traffic, a
 * delegation's per-round tool-call ticker, a report's file/checks detail, and compaction notices.
 * With [debug] off, only headline-level lines print — a phase boundary, a delegation's one-line
 * headline and outcome, a gate's healthy/unhealthy verdict.
 */
class CliTaskObserver(
    private val terminal: Terminal,
    private val debug: Boolean = false,
) : HrsTaskCompleter.Observer {
  /** The current delegation's tool-round counter — reset at [observeDelegationStarted]. */
  private var delegationRound = 0

  private fun heading(
      text: String,
  ) {
    terminal.println(TextStyles.bold(text))
  }

  private fun debugLine(
      text: String,
  ) {
    if (debug) terminal.println(TextColors.gray(text))
  }

  /** The first non-blank line of a Markdown block — this class's "headline" convention. */
  private fun headline(
      markdown: String,
  ): String = markdown.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""

  override fun observePhase(
      phase: HrsPipelinePhase,
  ) {
    heading("▸ ${phaseLabel(phase)}")
  }

  override fun observeEngineBanner(
      banner: HrsEngineBanner,
  ) {
    terminal.println(TextColors.cyan(bannerLine(banner)))
  }

  override fun observeRunCost(
      cost: HrsRunCost,
  ) {
    terminal.println(TextColors.cyan(runCostLine(cost)))
  }

  override fun observeEngineWarning(
      message: String,
  ) {
    terminal.println(TextColors.yellow("⚠ $message"))
  }

  override fun observeAgentAction(
      summary: String,
  ) {
    terminal.println(headline(summary))
  }

  override fun observeImplementationPlan(
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
  ) {
    heading("▸ Implementation plan")
    terminal.println(implementationPlan.body)
  }

  override fun observeCompaction(
      kind: HrsChunkSummaryKind,
      delegationRange: IntRange,
      summary: HrsChunkSummary,
  ) {
    debugLine("  · compacted $kind delegations $delegationRange")
  }

  override fun observeDelegationStarted(
      taskDefinition: HrsTaskDefinition,
  ) {
    delegationRound = 0
    terminal.println(TextColors.magenta("▸ Delegating: ${headline(taskDefinition.markdown)}"))
  }

  override fun observeDelegationReport(
      report: HrsDelegationReport,
  ) {
    val (color, label) =
        when (report.outcome) {
          HrsDelegationOutcome.Done -> TextColors.green to "done"
          HrsDelegationOutcome.PartiallyDone -> TextColors.yellow to "partial"
          HrsDelegationOutcome.Failed -> TextColors.red to "failed"
        }

    terminal.println(color("  ✓ $label — ${headline(report.narrative)}"))

    if (debug) {
      report.filesTouched
          .lineSequence()
          .filter { it.isNotBlank() }
          .forEach { line -> debugLine("    $line") }
      debugLine("    checks: ${headline(report.checksSummary)}")
    }
  }

  override fun observeGateResult(
      healthStatus: ProjectHealthStatus,
  ) {
    when (healthStatus) {
      ProjectHealthStatus.Healthy -> terminal.println(TextColors.green("✓ Gate: healthy"))
      is ProjectHealthStatus.Unhealthy -> {
        terminal.println(TextColors.red("✗ Gate: unhealthy"))
        debugLine(healthStatus.failureReport.toMarkdown())
      }
    }
  }

  override fun observeRawLeaderResponse(
      responseText: String,
  ) {
    debugLine("  leader » $responseText")
  }

  override fun observeRawAssistantResponse(
      responseText: String,
  ) {
    delegationRound += 1
    debugLine("  round $delegationRound » $responseText")
  }

  override fun observeScouting(): HrsTaskCompleter.ScoutingObserver = ScoutingObserverImpl()

  override fun observeSolutionImplementation(): HrsTaskCompleter.SolutionImplementationObserver =
      SolutionImplementationObserverImpl()

  override fun observeWorkspaceBriefing(): HrsTaskCompleter.WorkspaceBriefingObserver =
      object : HrsTaskCompleter.WorkspaceBriefingObserver {
        override fun observeRawResponse(
            responseText: String,
        ) = debugLine("  briefing » $responseText")
      }

  private inner class ScoutingObserverImpl : HrsTaskCompleter.ScoutingObserver {
    override fun observeRound(
        roundNumber: Int,
        baseEditorWorktree: VedWorktree,
        scoutMessage: HrsFrontlineAiSystem.ScoutMessage,
    ) {
      terminal.println("  round $roundNumber: ${headline(scoutMessage.body)}")
    }

    override fun observeRawResponse(
        responseText: String,
    ) = debugLine("  scout » $responseText")
  }

  private inner class SolutionImplementationObserverImpl :
      HrsTaskCompleter.SolutionImplementationObserver {
    override fun observeImplementation(
        attemptNumber: Int,
        patchMessage: HrsFrontlineAiSystem.PatchMessage,
    ) {
      debugLine("  attempt $attemptNumber » ${headline(patchMessage.body)}")
    }

    override fun observeHealthStatus(
        healthStatus: ProjectHealthStatus,
    ) = observeGateResult(healthStatus)

    override fun observeRawResponse(
        responseText: String,
    ) = debugLine("  implement » $responseText")
  }

  private companion object {
    fun phaseLabel(
        phase: HrsPipelinePhase,
    ): String =
        when (phase) {
          HrsPipelinePhase.WorkspacePreparing -> "Preparing workspace"
          HrsPipelinePhase.HealthGate -> "Running initial health gate"
          HrsPipelinePhase.Scouting -> "Scouting the codebase"
          HrsPipelinePhase.WorkspaceBriefing -> "Preparing workspace briefing"
          HrsPipelinePhase.ImplementationPlanning -> "Planning the implementation"
          is HrsPipelinePhase.ImplementationAttempt ->
              "Implementation attempt ${phase.attemptNumber} of ${phase.maxAttempts}"
          is HrsPipelinePhase.HealthCheck -> "Health check for attempt ${phase.attemptNumber}"
        }

    fun bannerLine(
        banner: HrsEngineBanner,
    ): String = buildString {
      append("Powered by ${banner.engineName}")
      banner.cliVersion?.let { append(" · CLI $it") }
      banner.model?.let { append(" · model $it") }
      append(" · ")
      append(
          when (banner.runMode) {
            HrsEngineRunMode.Gated -> "gated mode"
            HrsEngineRunMode.ManifestLess -> "manifest-less mode"
          },
      )
    }

    fun runCostLine(
        cost: HrsRunCost,
    ): String = buildString {
      append(cost.totalCostUsd?.let { "$${"%.4f".format(it)}" } ?: "cost unknown")
      cost.numTurns?.let { append(" · $it ${if (it == 1) "turn" else "turns"}") }
      cost.durationMs?.let { append(" · ${"%.1f".format(it / 1000.0)}s") }
    }
  }
}
