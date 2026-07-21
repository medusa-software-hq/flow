package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option

/**
 * `flow pipelines` — list issue pipelines from the deployed Flow API (the web app's pipelines
 * list), optionally filtered to one repo.
 */
class PipelinesCommand : CliktCommand(name = "pipelines") {
  private val repo by
      option("--repo", "-r", help = "Only pipelines for this owner/name repo (default: all repos).")

  override fun help(context: Context) = "List issue pipelines (newest first)."

  override fun run() {
    val pipelines = withFlowApiClient { it.listIssuePipelines(repoFullName = repo) }
    echo(formatPipelineTable(pipelines))
  }
}
