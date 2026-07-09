package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.nio.file.Path
import kotlin.io.path.readText
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.harness.HrsTaskDescription

/**
 * Only [ScoutFullyCommand]/[CompleteTaskCommand] need `--workdir`/`--task`; [WorkCommand] operates
 * on sessions cloned dynamically, so these options are optional at the parser level and validated
 * lazily via [GlobalState.loadGitWorktreeAndTask].
 */
class RootCommand : CliktCommand() {
  data class GlobalState(
      val workDirPath: Path?,
      val taskDescriptionPath: Path?,
  ) {
    suspend fun loadGitWorktreeAndTask(): Pair<GitWorktree, HrsTaskDescription> {
      val workDirPath = workDirPath ?: throw CliktError("--workdir is required for this command")
      val taskDescriptionPath =
          taskDescriptionPath ?: throw CliktError("--task is required for this command")

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

      return gitWorktree to taskDescription
    }
  }

  private val workDirPath: Path? by
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

  private val taskDescriptionPath: Path? by
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

  override fun run() {
    currentContext.obj =
        GlobalState(
            workDirPath = workDirPath,
            taskDescriptionPath = taskDescriptionPath,
        )
  }
}
