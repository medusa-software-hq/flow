package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/** `flow sessions` — list sessions from the deployed Flow API (the web app's sessions list). */
class SessionsCommand : CliktCommand(name = "sessions") {
  override fun help(context: Context) = "List Flow sessions (newest first)."

  override fun run() {
    val sessions = withFlowApiClient { it.listSessions() }
    echo(formatSessionTable(sessions))
  }
}
