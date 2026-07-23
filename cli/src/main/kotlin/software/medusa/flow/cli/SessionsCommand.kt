package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt

/** `flow sessions` — inspect Flow sessions on the deployed API. A group; see the subcommands. */
class SessionsCommand : NoOpCliktCommand(name = "sessions") {
  override fun help(context: Context) = "Inspect Flow sessions."
}

/** `flow sessions list` — the web app's sessions list, newest first. */
class SessionsListCommand : CliktCommand(name = "list") {
  override fun help(context: Context) = "List sessions (newest first)."

  override fun run() {
    val sessions = withFlowApiClient { it.listSessions() }
    echo(formatSessionTable(sessions))
  }
}

/** `flow sessions show <id>` — one session's detail and its event log (what the engine did). */
class SessionsShowCommand : CliktCommand(name = "show") {
  private val id by argument("id", help = "The session id (see `flow sessions list`).")

  override fun help(context: Context) = "Show a session's detail and event log."

  override fun run() {
    val response = withFlowApiClient { it.getSession(id) }
    echo(formatSessionDetail(response.session, response.eventsList))
  }
}

/**
 * `flow sessions abort <id>` — the "stop": aborts a RUNNING session. The worker holding it learns
 * via the ABORTED write-ack on its next heartbeat and kills the engine. FAILED_PRECONDITION (a
 * clean one-line error) if the session isn't running.
 */
class SessionsAbortCommand : CliktCommand(name = "abort") {
  private val id by argument("id", help = "The session id (see `flow sessions list`).")

  private val assumeYes by
      option("--yes", "-y", help = "Skip the confirmation prompt.").flag(default = false)

  override fun help(context: Context) = "Abort a running session (stop the worker)."

  override fun run() {
    if (
        !assumeYes && YesNoPrompt("Abort session $id?", Terminal(), default = false).ask() != true
    ) {
      echo("Aborted.")
      return
    }

    val session = withFlowApiClient { it.abortSession(id) }
    echo("Aborted session ${session.id} (${sessionStateLabel(session.state)}).")
  }
}
