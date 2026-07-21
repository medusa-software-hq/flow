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
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.virtual_editor.worktree.VedWorktree

private fun heading(text: String): String = (TextStyles.bold + TextColors.brightCyan)("── $text ──")

private fun phaseHeadingText(
    phase: HrsPipelinePhase,
): String =
    when (phase) {
      HrsPipelinePhase.WorkspacePreparing -> "Preparing workspace"
      HrsPipelinePhase.HealthGate -> "Initial health gate"
      HrsPipelinePhase.Scouting -> "Scouting"
      HrsPipelinePhase.WorkspaceBriefing -> "Workspace briefing"
      HrsPipelinePhase.ImplementationPlanning -> "Implementation planning"
      is HrsPipelinePhase.ImplementationAttempt ->
          "Implementation · attempt ${phase.attemptNumber} of ${phase.maxAttempts}"
      is HrsPipelinePhase.HealthCheck -> "Health check · attempt ${phase.attemptNumber}"
    }

/** A [HrsTaskCompleter.Observer] that narrates task completion to the terminal in color. */
class CliTaskObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.Observer {
  override fun observeScouting(): HrsTaskCompleter.ScoutingObserver =
      CliScoutingObserver(terminal = terminal)

  override fun observeSolutionImplementation(): HrsTaskCompleter.SolutionImplementationObserver =
      CliSolutionImplementationObserver(terminal = terminal)

  override fun observeWorkspaceBriefing(): HrsTaskCompleter.WorkspaceBriefingObserver =
      CliWorkspaceBriefingObserver(terminal = terminal)

  override fun observeImplementationPlan(
      implementationPlan: HrsExpertAiSystem.ImplementationPlan,
  ) {
    terminal.println("> Implementation plan:")
    terminal.println()
    terminal.printCode(implementationPlan.body)
  }

  override fun observePhase(
      phase: HrsPipelinePhase,
  ) {
    terminal.println()
    terminal.println(heading(phaseHeadingText(phase)))
  }

  override fun observeEngineBanner(
      banner: HrsEngineBanner,
  ) {
    val mode =
        when (banner.runMode) {
          HrsEngineRunMode.Gated -> "gated"
          HrsEngineRunMode.ManifestLess -> "manifest-less"
        }
    val details =
        listOfNotNull(
                banner.cliVersion?.let { "CLI $it" },
                banner.model?.let { "model $it" },
                "$mode mode",
            )
            .joinToString(" · ")
    terminal.println()
    terminal.println((TextStyles.bold + TextColors.magenta)("Powered by ${banner.engineName}"))
    terminal.println(TextColors.gray(details))
  }

  override fun observeAgentAction(
      summary: String,
  ) {
    terminal.println(TextColors.gray("· ") + summary)
  }

  override fun observeRunCost(
      cost: HrsRunCost,
  ) {
    val parts =
        listOfNotNull(
            cost.totalCostUsd?.let { "$${"%.4f".format(it)}" },
            cost.numTurns?.let { "$it ${if (it == 1) "turn" else "turns"}" },
            cost.durationMs?.let { "${"%.1f".format(it / 1000.0)}s" },
        )
    terminal.println()
    terminal.println(
        TextColors.brightCyan("Cost: ${parts.joinToString(" · ").ifEmpty { "unknown" }}")
    )
  }
}

class CliWorkspaceBriefingObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.WorkspaceBriefingObserver {
  override fun observeRawResponse(
      responseText: String,
  ) {
    terminal.printUnstructuredCompletionResponse(responseText = responseText)
  }
}

/**
 * A [HrsTaskCompleter.SolutionImplementationObserver] that narrates each implementation round. The
 * frontline's free-form message is printed by [observeRawResponse]; this only reports the resulting
 * health — the attempt heading itself comes from [CliTaskObserver.observePhase].
 */
class CliSolutionImplementationObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.SolutionImplementationObserver {
  override fun observeImplementation(
      attemptNumber: Int,
      patchMessage: PatchMessage,
  ) = Unit

  override fun observeHealthStatus(
      healthStatus: ProjectHealthStatus,
  ) {
    terminal.println()

    when (healthStatus) {
      ProjectHealthStatus.Healthy -> terminal.println(TextColors.green("✓ Health checks passed"))

      is ProjectHealthStatus.Unhealthy -> {
        val failureReport = healthStatus.failureReport

        val stageName =
            when (failureReport.stage) {
              ProjectFailureReport.Stage.Analysis -> "Analysis"
              ProjectFailureReport.Stage.Testing -> "Tests"
            }

        terminal.println(TextColors.red("✗ $stageName failed:"))

        failureReport.failure.failureByModulePath.forEach { (modulePath, moduleFailure) ->
          terminal.println()
          terminal.println("Module ${TextColors.yellow(modulePath.toUnixAbsolutePathString())}:")
          terminal.println()
          terminal.printCode(moduleFailure.diagnosticOutput)
        }
      }
    }
  }

  override fun observeRawResponse(
      responseText: String,
  ) {
    terminal.printUnstructuredCompletionResponse(responseText = responseText)
  }
}

/**
 * A [HrsTaskCompleter.ScoutingObserver] that frames each scouting round. The frontline's free-form
 * message is printed by [observeRawResponse].
 */
class CliScoutingObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.ScoutingObserver {
  override fun observeRound(
      roundNumber: Int,
      baseEditorWorktree: VedWorktree,
      scoutMessage: ScoutMessage,
  ) {
    terminal.println()
    terminal.println(heading("Scouting · round $roundNumber"))
  }

  override fun observeRawResponse(
      responseText: String,
  ) {
    terminal.printUnstructuredCompletionResponse(responseText = responseText)
  }
}

private fun Terminal.printUnstructuredCompletionResponse(
    responseText: String,
) {
  println("> Raw response:")
  println()
  printCode(responseText)
}
