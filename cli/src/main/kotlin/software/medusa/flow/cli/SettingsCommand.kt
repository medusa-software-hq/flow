package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.choice

/**
 * `flow settings` — Flow's global Quick Settings (currently just `auto_merge`), mirroring the web
 * app's Quick Settings panel. Bare, it shows the current values; `settings set <name> <value>`
 * changes one.
 */
class SettingsCommand : CliktCommand(name = "settings") {
  override fun help(context: Context) = "Show Flow's global Quick Settings."

  override fun run() {
    // A CliktCommand with subcommands runs its own body before dispatching into the invoked one —
    // skip that here so `flow settings set auto-merge on` doesn't also print the stale settings.
    if (currentContext.invokedSubcommand != null) return

    val settings = withFlowApiClient { it.getSettings() }
    echo(formatSettings(settings))
  }
}

/** `flow settings set` — a pure group; see the subcommands (one per setting). */
class SettingsSetCommand : NoOpCliktCommand(name = "set") {
  override fun help(context: Context) = "Change one Quick Setting."
}

/** `flow settings set auto-merge <on|off>` — the web app's Auto-merge toggle. */
class SettingsSetAutoMergeCommand : CliktCommand(name = "auto-merge") {
  private val value by argument("value", help = "on or off.").choice("on", "off")

  override fun help(context: Context) =
      "Turn auto-merge on or off: Flow arms GitHub auto-merge on the PRs it drives."

  override fun run() {
    val settings = withFlowApiClient { it.updateSettings(autoMerge = value == "on") }
    echo(formatSettings(settings))
  }
}
