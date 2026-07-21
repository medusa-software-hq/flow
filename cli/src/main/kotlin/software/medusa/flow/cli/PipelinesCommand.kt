package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.parameters.options.option

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
