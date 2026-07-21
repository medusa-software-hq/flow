package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage

/**
 * `flow login` — the loopback + PKCE browser sign-in; caches the refresh token so the read commands
 * can mint ID tokens later without another browser round-trip.
 */
class LoginCommand : CliktCommand(name = "login") {
  override fun help(context: Context) =
      "Sign in with your Google account and cache the session for read commands."

  override fun run() {
    val config =
        try {
          FlowConfig.fromEnvironment()
        } catch (e: FlowConfig.Companion.MissingException) {
          throw PrintMessage(
              e.message ?: "Missing configuration.",
              statusCode = 1,
              printError = true,
          )
        }

    val tokens =
        try {
          FlowOAuth(
                  clientId = config.oauthClientId,
                  clientSecret = config.oauthClientSecret,
                  hostedDomain = config.allowedDomain,
              )
              .login(echo = { echo(it) })
        } catch (e: FlowOAuthException) {
          throw PrintMessage("Sign-in failed: ${e.message}", statusCode = 1, printError = true)
        }

    val refreshToken =
        tokens.refreshToken
            ?: throw PrintMessage(
                "Google did not return a refresh token, so the session can't be cached. Try again.",
                statusCode = 1,
                printError = true,
            )
    val email = FlowJwt.email(tokens.idToken) ?: "unknown"
    saveFlowCredentials(
        FlowCredentials(refreshToken, tokens.idToken, tokens.expiresAtEpochSec, email),
    )
    echo("Signed in as $email")
  }
}
