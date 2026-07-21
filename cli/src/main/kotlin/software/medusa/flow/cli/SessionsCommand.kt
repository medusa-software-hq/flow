package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument

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
