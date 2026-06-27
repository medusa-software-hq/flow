package software.medusa.flow.cli

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingResult
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedEntityAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

private fun heading(text: String): String = (TextStyles.bold + TextColors.brightCyan)("── $text ──")

private fun indent(level: Int): String = "  ".repeat(level)

/** A [HrsTaskCompleter.Observer] that narrates task completion to the terminal in color. */
class CliTaskObserver(
    private val terminal: Terminal,
) : HrsTaskCompleter.Observer {
  override fun observeScouting(): HrsTaskCompleter.ScoutingObserver =
      CliScoutingObserver(terminal = terminal)

  override fun observeImplementedSolution(
      solutionPatch: VedWorktreePatch,
  ) {
    terminal.println()
    terminal.println(heading("Implemented solution"))
    terminal.println()

    val fileEdits = solutionPatch.rootDirectoryPatch.collectFileEdits(pathPrefix = "")

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
      scoutingResult: ScoutingResult,
  ) {
    roundNumber++

    terminal.println()
    terminal.println(heading("Scouting · round $roundNumber"))
    terminal.println()

    when (scoutingResult) {
      ScoutingResult.Completed ->
          terminal.println(
              TextColors.green("✓ Scouting complete — all relevant files are open"),
          )

      is ScoutingResult.Continued -> {
        val scoutRequest = scoutingResult.scoutRequest

        terminal.println(
            (TextStyles.italic + TextColors.gray(0.6))(scoutRequest.rationale.render().trim()),
        )
        terminal.println()
        terminal.println(TextStyles.bold("Requested:"))

        scoutRequest.requestedAdjustment.rootDirectoryAdjustment.printAsTree(name = "", level = 0)
      }
    }
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
