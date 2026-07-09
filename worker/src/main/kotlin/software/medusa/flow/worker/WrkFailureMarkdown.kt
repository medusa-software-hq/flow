package software.medusa.flow.worker

import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.flow.harness.HrsTaskCompleter.JointOperationPhase
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult.Failure
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.universal_project.UnpModuleConnection

private fun jointOperationPhaseName(
    phase: JointOperationPhase,
): String =
    when (phase) {
      JointOperationPhase.ProjectBootstrapping -> "Project bootstrap"
      JointOperationPhase.InitialProjectAnalysis -> "Initial analysis"
      JointOperationPhase.InitialProjectTesting -> "Initial tests"
    }

private fun ProjectFailureReport.stageName(): String =
    when (stage) {
      ProjectFailureReport.Stage.Analysis -> "Analysis"
      ProjectFailureReport.Stage.Testing -> "Tests"
    }

private fun failureByModulePathMarkdown(
    failureByModulePath: Map<UfsLiteralAbsolutePath, UnpModuleConnection.Result.Failure>,
): String =
    failureByModulePath.entries.joinToString("\n\n") { (modulePath, moduleFailure) ->
      "**${modulePath.toUnixAbsolutePathString()}**\n\n```\n${moduleFailure.diagnosticOutput}\n```"
    }

fun ProjectFailureReport.toMarkdown(): String =
    "**${stageName()} failed:**\n\n${failureByModulePathMarkdown(failure.failureByModulePath)}"

fun Failure.toMarkdown(): String =
    when (this) {
      is Failure.JointOperation ->
          "**${jointOperationPhaseName(phase)} failed:**\n\n" +
              failureByModulePathMarkdown(operationFailure.failureByModulePath)

      is Failure.AttemptsExhausted ->
          "**Still unhealthy after $attemptsMade implementation attempt(s):**\n\n" +
              lastHealthStatus.failureReport.toMarkdown()
    }
