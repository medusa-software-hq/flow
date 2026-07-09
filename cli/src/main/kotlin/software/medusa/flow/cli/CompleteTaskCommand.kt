package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.runBlocking
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport

class CompleteTaskCommand(
    private val terminal: Terminal,
    private val taskCompleter: HrsTaskCompleter,
) :
    CliktCommand(
        name = "complete-task",
    ) {
  companion object {
    private val targetWorkspacePrefix = UfsName.Literal("target-workspace-")

    /** Exit code for every [TaskCompletionResult.Failure] variant. */
    private const val failureExitCode = 1
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
              observer = CliTaskObserver(terminal = terminal),
          )

      render(taskCompletionResult = taskCompletionResult)
    }
  }

  /**
   * A function (rather than an inline statement-position `when`) so its `Unit` return type forces
   * the compiler to check exhaustiveness — a new [TaskCompletionResult.Failure] subtype must be
   * handled here, not silently dropped.
   */
  private suspend fun render(
      taskCompletionResult: TaskCompletionResult,
  ): Unit =
      when (taskCompletionResult) {
        is TaskCompletionResult.Success -> {
          taskCompletionResult.temporaryWorkspace.use { workspace ->
            val targetDirectory = UfsNioDirectory.createTemporary(prefix = targetWorkspacePrefix)

            terminal.println(
                "Target directory: ${TextColors.brightGreen(targetDirectory.directoryPath.toString())}",
            )

            workspace.rootDirectory.copyRecursivelyTo(targetDirectory = targetDirectory)
          }

          terminal.println(TextColors.green("Task completed"))
        }

        is TaskCompletionResult.Failure.JointOperation -> {
          val phaseName =
              when (taskCompletionResult.phase) {
                HrsTaskCompleter.JointOperationPhase.ProjectBootstrapping -> "Project bootstrap"
                HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis -> "Initial analysis"
                HrsTaskCompleter.JointOperationPhase.InitialProjectTesting -> "Initial tests"
              }

          terminal.println(TextColors.red("✗ $phaseName failed:"))
          terminal.println()

          taskCompletionResult.operationFailure.failureByModulePath.forEach {
              (modulePath, moduleFailure) ->
            terminal.println("Module ${TextColors.yellow(modulePath.toUnixAbsolutePathString())}:")
            terminal.println()
            terminal.printCode(moduleFailure.diagnosticOutput)
          }

          throw ProgramResult(statusCode = failureExitCode)
        }

        is TaskCompletionResult.Failure.AttemptsExhausted -> {
          val stageName =
              when (taskCompletionResult.lastHealthStatus.failureReport.stage) {
                ProjectFailureReport.Stage.Analysis -> "Analysis"
                ProjectFailureReport.Stage.Testing -> "Tests"
              }

          terminal.println(
              TextColors.red(
                  "✗ Still unhealthy after ${taskCompletionResult.attemptsMade} implementation " +
                      "attempt(s) ($stageName failed):",
              ),
          )
          terminal.println()

          taskCompletionResult.lastHealthStatus.failureReport.failure.failureByModulePath.forEach {
              (modulePath, moduleFailure) ->
            terminal.println("Module ${TextColors.yellow(modulePath.toUnixAbsolutePathString())}:")
            terminal.println()
            terminal.printCode(moduleFailure.diagnosticOutput)
          }

          throw ProgramResult(statusCode = failureExitCode)
        }
      }
}
