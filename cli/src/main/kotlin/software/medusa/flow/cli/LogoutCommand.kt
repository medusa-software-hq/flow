package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

/** `flow logout` — forget the cached session on this machine. */
class LogoutCommand : CliktCommand(name = "logout") {
  override fun help(context: Context) = "Forget the cached Flow session on this machine."

  override fun run() {
    deleteFlowCredentials()
    echo("Signed out.")
  }
}
