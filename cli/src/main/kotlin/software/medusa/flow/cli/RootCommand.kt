package software.medusa.flow.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand

/**
 * The `flow` root command — a pure group. It carries no global options: the worker loop (`work`)
 * reads its config from the environment, and the read commands (`sessions`, `pipelines`) plus
 * `login`/`logout` are self-contained. The offline `--workdir`/`--task` engine commands it used to
 * host were dropped when the CLI became a read-only API client.
 */
class RootCommand : NoOpCliktCommand(name = "flow") {
  override fun help(context: Context) =
      "Flow CLI — a read-only client for the deployed Flow API, plus the worker loop."
}
