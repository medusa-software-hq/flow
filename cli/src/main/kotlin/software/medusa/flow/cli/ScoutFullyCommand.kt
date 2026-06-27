package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import software.medusa.commons.markdown.MdDocument
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.Companion.scoutFully
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.render

class ScoutFullyCommand(
    private val terminal: Terminal,
    private val aiSystem: HrsFrontlineAiSystem,
) :
    CliktCommand(
        name = "scout-fully",
    ) {
  private val globalState by requireObject<RootCommand.GlobalState>()

  override fun run() {
    runBlocking {
      val gitWorktree = globalState.gitWorktree
      val taskDescription = globalState.taskDescription

      val fullScoutingResult =
          aiSystem.scoutFully(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
              scoutingObserver = CliScoutingObserver(terminal = terminal),
          )

      terminal.println(
          "Final timestamp: ${TextColors.blue("t = ${fullScoutingResult.finalTimestamp.t}")}"
      )
      terminal.println()

      val fullyScoutedWorktreeChapter = fullScoutingResult.fullyScoutedWorktree.render()

      terminal.println("Fully scouted worktree:")
      terminal.println()

      terminal.printCode(
          MdDocument(rootChapter = fullyScoutedWorktreeChapter).render(),
      )
    }
  }
}
