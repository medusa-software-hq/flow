package software.medusa.flow.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchCommand
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutCommand
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch

private fun heading(text: String): String = (TextStyles.bold + TextColors.brightCyan)("── $text ──")

private fun indent(level: Int): String = "  ".repeat(level)

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
}

class CliWorkspaceBriefingObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.WorkspaceBriefingObserver {
  override fun observeRawResponse(
      response: OaiConfiguredClient.UnstructuredCompletionResponse,
  ) {
    terminal.printUnstructuredCompletionResponse(response = response)
  }
}

/**
 * A [HrsTaskCompleter.SolutionImplementationObserver] that prints each attempted patch as a
 * diff-like summary.
 */
class CliSolutionImplementationObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.SolutionImplementationObserver {
  private var attemptNumber = 0

  override fun observeImplementation(
      patchCommand: PatchCommand,
  ) {
    attemptNumber++

    terminal.println()
    terminal.println(heading("Solution · attempt $attemptNumber"))
    terminal.println()

    val fileEdits =
        patchCommand.solutionPatch.rootDirectoryPatch.collectFileEdits(
            pathPrefix = "",
        )

    if (fileEdits.isEmpty()) {
      terminal.println(TextColors.gray(0.5)("(no changes)"))
      return
    }

    fileEdits.forEach { (path, filePatch) ->
      terminal.println((TextStyles.bold + TextColors.brightCyan)(path))

      filePatch.txtPatch.fragmentByOldLineIndexRange.entries
          .sortedBy { (range, _) -> range.startIndex.indexZeroBased }
          .forEach { (range, fragment) -> printEdit(range = range, fragment = fragment) }

      terminal.println()
    }
  }

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
      response: OaiConfiguredClient.UnstructuredCompletionResponse,
  ) {
    terminal.printUnstructuredCompletionResponse(
        response = response,
    )
  }

  private fun VedDirectoryPatch.collectFileEdits(
      pathPrefix: String,
  ): List<Pair<String, VedFilePatch>> =
      childPatchByName.entries
          .sortedBy { (name, _) -> name.content }
          .flatMap { (name, childPatch) ->
            val childPath = "$pathPrefix/${name.content}"

            when (childPatch) {
              is VedFilePatch -> listOf(childPath to childPatch)
              is VedDirectoryPatch -> childPatch.collectFileEdits(pathPrefix = childPath)
            }
          }

  private fun printEdit(
      range: TxtLineIndexRange,
      fragment: TxtPatch.Fragment,
  ) {
    val startOneBased = range.startIndex.indexOneBased
    val endOneBased = range.endIndexExclusive.indexZeroBased

    when {
      range.startIndex == range.endIndexExclusive -> {
        terminal.println(indent(1) + TextColors.green("+ INSERT BEFORE $startOneBased"))
        printContent(fragment.newContent.lines.map { it.content })
      }

      fragment.newContent.lines.isEmpty() ->
          terminal.println(indent(1) + TextColors.red("- DELETE $startOneBased-$endOneBased"))

      else -> {
        terminal.println(indent(1) + TextColors.yellow("~ UPDATE $startOneBased-$endOneBased"))
        printContent(fragment.newContent.lines.map { it.content })
      }
    }
  }

  private fun printContent(
      lines: List<String>,
  ) {
    lines.forEach { line ->
      terminal.println(indent(2) + TextColors.gray(0.4)("│ ") + TextColors.gray(0.6)(line))
    }
  }
}

/** A [HrsTaskCompleter.ScoutingObserver] that narrates each scouting round to the terminal. */
class CliScoutingObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.ScoutingObserver {
  private var roundNumber = 0

  override fun observeRound(
      baseEditorWorktree: VedWorktree,
      scoutCommand: ScoutCommand,
  ) {
    roundNumber++

    terminal.println()
    terminal.println(heading("Scouting · round $roundNumber"))
    terminal.println()

    when (scoutCommand) {
      ScoutCommand.Stop ->
          terminal.println(
              TextColors.green("✓ Scouting complete — all relevant files are open"),
          )

      is ScoutCommand.Continue -> {
        terminal.println(
            (TextStyles.italic + TextColors.gray(0.6))(scoutCommand.rationale.render().trim()),
        )
        terminal.println()
        terminal.println(TextStyles.bold("Requested:"))

        scoutCommand.requestedAdjustment.rootDirectoryAdjustment.printAsTree(name = "", level = 0)
      }
    }
  }

  override fun observeRawResponse(
      response: OaiConfiguredClient.UnstructuredCompletionResponse,
  ) {
    terminal.printUnstructuredCompletionResponse(
        response = response,
    )
  }

  private fun VedEntityAdjustment.printAsTree(
      name: String,
      level: Int,
  ) {
    when (this) {
      is VedDirectoryAdjustment.Dive -> {
        terminal.println(indent(level) + TextColors.brightBlue("$name/"))

        childAdjustmentByName.entries
            .sortedBy { (childName, _) -> childName.content }
            .forEach { (childName, childAdjustment) ->
              childAdjustment.printAsTree(name = childName.content, level = level + 1)
            }
      }

      VedDirectoryAdjustment.Expand ->
          terminal.println(
              indent(level) + TextColors.yellow("$name/") + TextColors.gray(0.5)("  EXPAND"),
          )

      VedFileAdjustment.Open ->
          terminal.println(
              indent(level) + TextColors.green(name) + TextColors.gray(0.5)("  OPEN"),
          )
    }
  }
}

private fun Terminal.printUnstructuredCompletionResponse(
    response: OaiConfiguredClient.UnstructuredCompletionResponse,
) {
  println("> Raw response:")
  println()
  printCode(response.responseText)
}
