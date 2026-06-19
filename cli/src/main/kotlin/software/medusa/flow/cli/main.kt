package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsProperSolutionCoder
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.HrsProperTemporaryWorkspaceAllocator
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription

private val terminal = Terminal()

class MainCommand(
    private val taskCompleter: HrsTaskCompleter,
) : CliktCommand() {
  companion object {
    private val targetWorkspacePrefix = UfsName.Literal("target-workspace-")
  }

  private val workDirPath: Path by
      argument(
              help = "The path to a working directory",
          )
          .path(
              mustExist = true,
              canBeFile = false,
              mustBeReadable = true,
          )

  private val taskDescriptionPath: Path by
      argument(
              help = "The path to the task description",
          )
          .path(
              mustExist = true,
              canBeDir = false,
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

      val taskDescriptionText = taskDescriptionPath.readText()
      val taskDescriptionDocument = MdDocument.parse(markdownSource = taskDescriptionText)

      val taskDescription =
          HrsTaskDescription(
              body = taskDescriptionDocument.rootChapter.element,
          )

      taskCompleter
          .completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
          )
          .use { temporaryWorkspace ->
            val targetDirectory = UfsNioDirectory.createTemporary(prefix = targetWorkspacePrefix)

            terminal.println(
                "Target directory: ${TextColors.brightGreen(targetDirectory.directoryPath.toString())}"
            )

            temporaryWorkspace.rootDirectory.copyRecursivelyTo(targetDirectory = targetDirectory)
          }

      terminal.println(TextColors.green("Task completed"))
    }
  }
}

suspend fun main(
    args: Array<String>,
) {
  coroutineScope {
    val openRouterApiKey =
        OaiApiKey(
            System.getenv("OPENROUTER_API_KEY")
                ?: error("OPENROUTER_API_KEY environment variable is not set"),
        )

    val temporaryWorkspaceAllocator =
        HrsProperTemporaryWorkspaceAllocator.prepare(
            coroutineScope = this,
        )

    val openAiClient =
        OaiProperClient.withTarget(
                targetBaseUrl = OaiConfiguredClient.openRouterBaseUrl,
                targetApiKey = openRouterApiKey,
            )
            .withModel(
                model = OaiModel.DeepSeekFlash,
            )

    val solutionCoder =
        HrsProperSolutionCoder(
            openaiClient = openAiClient,
        )

    val taskCompleter =
        HrsProperTaskCompleter(
            temporaryWorkspaceAllocator = temporaryWorkspaceAllocator,
            solutionCoder = solutionCoder,
        )

    MainCommand(
            taskCompleter = taskCompleter,
        )
        .main(args)
  }
}
