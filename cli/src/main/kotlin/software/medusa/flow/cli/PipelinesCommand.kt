package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt

/** `flow pipelines` — inspect issue pipelines on the deployed API. A group; see the subcommands. */
class PipelinesCommand : NoOpCliktCommand(name = "pipelines") {
  override fun help(context: Context) = "Inspect issue pipelines."
}

/** `flow pipelines list` — the web app's pipelines list, optionally filtered to one repo. */
class PipelinesListCommand : CliktCommand(name = "list") {
  private val repo by
      option("--repo", "-r", help = "Only pipelines for this owner/name repo (default: all repos).")

  override fun help(context: Context) = "List issue pipelines (newest first)."

  override fun run() {
    val pipelines = withFlowApiClient { it.listIssuePipelines(repoFullName = repo) }
    echo(formatPipelineTable(pipelines))
  }
}

/**
 * `flow pipelines clear <id>` — the web app's Clear button. Marks a FAILED pipeline cleared,
 * releasing the repo mutex so Flow can re-pick the still-`flow:ready` issue into a fresh run. The
 * API rejects any non-FAILED pipeline (surfaced as a clean `FAILED_PRECONDITION` error).
 */
class PipelinesClearCommand : CliktCommand(name = "clear") {
  private val id by argument("id", help = "The pipeline id to clear (see `flow pipelines list`).")

  private val assumeYes by
      option("--yes", "-y", help = "Skip the confirmation prompt.").flag(default = false)

  override fun help(context: Context) = "Clear a FAILED pipeline so Flow can re-pick the issue."

  override fun run() {
    if (
        !assumeYes && YesNoPrompt("Clear pipeline $id?", Terminal(), default = false).ask() != true
    ) {
      echo("Aborted.")
      return
    }

    val pipeline = withFlowApiClient { it.clearIssuePipeline(id) }
    val issue =
        if (pipeline.issueNumber > 0) " (${pipeline.repoFullName} #${pipeline.issueNumber})" else ""
    echo("Cleared pipeline $id$issue. Flow re-picks the issue if it's still labeled flow:ready.")
  }
}
