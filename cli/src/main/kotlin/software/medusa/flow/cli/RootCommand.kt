package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.harness.HrsTaskDescription

class RootCommand : CliktCommand() {
  data class GlobalState(
      val gitWorktree: GitWorktree,
      val taskDescription: HrsTaskDescription,
  )

  val workDirPath: Path by
      option(
              "--workdir",
              "-w",
              help = "The path to a working directory",
          )
          .path(
              mustExist = true,
              canBeFile = false,
              mustBeReadable = true,
          )
          .required()

  private val taskDescriptionPath: Path by
      option(
              "--task",
              "-t",
              help = "The path to the task description",
          )
          .path(
              mustExist = true,
              canBeDir = false,
              mustBeReadable = true,
          )
          .required()

  override fun run() {
    runBlocking {
      val repoDirectory = UfsNioDirectory(directoryPath = workDirPath)

      val gitWorktree =
          GitWorktree.load(
              repoDirectory = repoDirectory,
              globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
          )

      val taskDescriptionText = taskDescriptionPath.readText()
      val taskDescriptionDocument = MdDocument.parse(markdownSource = taskDescriptionText)

      val taskDescription =
          HrsTaskDescription(
              body = taskDescriptionDocument.rootChapter.element,
          )

      currentContext.obj =
          GlobalState(
              gitWorktree = gitWorktree,
              taskDescription = taskDescription,
          )
    }
  }
}
