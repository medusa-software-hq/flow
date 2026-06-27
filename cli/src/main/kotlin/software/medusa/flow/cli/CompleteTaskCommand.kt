package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter

class CompleteTaskCommand(
    private val terminal: Terminal,
    private val taskCompleter: HrsTaskCompleter,
) :
    CliktCommand(
        name = "complete-task",
    ) {
  companion object {
    private val targetWorkspacePrefix = UfsName.Literal("target-workspace-")
  }

  private val globalState by requireObject<RootCommand.GlobalState>()

  override fun run() {
    runBlocking {
      val gitWorktree = globalState.gitWorktree
      val taskDescription = globalState.taskDescription

      val taskCompletionResult =
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
          )

      when (taskCompletionResult) {
        is HrsTaskCompleter.TaskCompletionResult.Success -> {
          taskCompletionResult.temporaryWorkspace.use { workspace ->
            val targetDirectory = UfsNioDirectory.createTemporary(prefix = targetWorkspacePrefix)

            terminal.println(
                "Target directory: ${TextColors.brightGreen(targetDirectory.directoryPath.toString())}",
            )

            workspace.rootDirectory.copyRecursivelyTo(targetDirectory = targetDirectory)
          }

          terminal.println(TextColors.green("Task completed"))
        }

        is HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation -> {
          val phaseName =
              when (taskCompletionResult.phase) {
                HrsTaskCompleter.JointOperationPhase.ProjectBootstrapping -> "Project bootstrap"
                HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis -> "Initial analysis"
                HrsTaskCompleter.JointOperationPhase.InitialProjectTesting -> "Initial tests"
                HrsTaskCompleter.JointOperationPhase.FinalProjectAnalysis -> "Final analysis"
                HrsTaskCompleter.JointOperationPhase.FinalProjectTesting -> "Final tests"
              }

          terminal.println(TextColors.red("✗ $phaseName failed:"))
          terminal.println()

          taskCompletionResult.operationFailure.failureByModulePath.forEach {
              (modulePath, moduleFailure) ->
            terminal.println("Module ${TextColors.yellow(modulePath.toUnixAbsolutePathString())}:")
            terminal.println()
            terminal.printCode(moduleFailure.diagnosticOutput)
          }
        }
      }
    }
  }
}
