package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.render

private val terminal = Terminal()

class MainCommand : CliktCommand() {
  private val workDirPath: Path by
      argument(
              help = "The path to a working directory",
          )
          .path(
              mustExist = true,
              canBeFile = false,
              mustBeReadable = true,
          )

  override fun run() {
    runBlocking {
      val repoDirectory =
          UfsNioDirectory(
              directoryPath = workDirPath,
          )

      val gitWorktree =
          GitWorktree.load(
              repoDirectory = repoDirectory,
              globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
          )

      val worktree =
          VedWorktree.import(
              sourceWorktree = gitWorktree,
          )

      val document =
          MdDocument(
              rootChapter = worktree.render(),
          )

      terminal.println("Document:")
      terminal.println(TextColors.brightRed(document.render()))
    }
  }
}

fun main(
    args: Array<String>,
) {
  MainCommand().main(args)
}
