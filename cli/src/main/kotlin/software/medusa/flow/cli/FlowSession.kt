package software.medusa.flow.cli

import java.nio.file.Path

/**
 * Raised when there's no usable Flow session — the caller turns it into a "run flow login" hint.
 */
class FlowNotLoggedInException(message: String) : Exception(message)

/**
 * Default token refresher: mints a fresh ID token via the configured OAuth client. Only invoked
 * when a cached token has actually expired, so a still-valid session never needs the client secret.
 */
private fun defaultRefresher(config: FlowConfig): (String) -> FlowTokenSet = { refreshToken ->
  FlowOAuth(
          clientId = config.oauthClientId,
          clientSecret = config.oauthClientSecret,
          hostedDomain = config.allowedDomain,
      )
      .refresh(refreshToken)
}

/**
 * Supplies a currently-valid Google ID token for Flow API calls: hands back the cached one while
 * it's still good, and silently refreshes it (no browser) when it's expired. Only a revoked/expired
 * refresh token forces a fresh `flow login`.
 */
class FlowSession(
    private val dir: Path = flowConfigDir(),
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val refresher: (String) -> FlowTokenSet,
) {
  companion object {
    /**
     * Builds a session whose refresher resolves OAuth config lazily, only when a refresh is due.
     */
    fun fromEnvironment(
        dir: Path = flowConfigDir(),
        lookup: (String) -> String? = System::getenv,
    ): FlowSession =
        FlowSession(
            dir = dir,
            refresher = { refreshToken ->
              defaultRefresher(FlowConfig.fromEnvironment(lookup))(refreshToken)
            },
        )
  }

  fun currentIdToken(): String {
    val credentials =
        loadFlowCredentials(dir)
            ?: throw FlowNotLoggedInException("Not signed in. Run 'flow login' first.")

    // Refresh a little early so a token doesn't expire mid-request.
    if (credentials.idTokenExpiresAtEpochSec > nowEpochSec() + 30) {
      return credentials.idToken
    }

    val refreshed =
        try {
          refresher(credentials.refreshToken)
        } catch (e: FlowOAuthException) {
          throw FlowNotLoggedInException("Session expired (${e.code}). Run 'flow login' again.")
        } catch (e: FlowConfig.Companion.MissingException) {
          throw FlowNotLoggedInException(
              "Session needs a refresh but ${e.message} Run 'flow login' again.",
          )
        }

    saveFlowCredentials(
        credentials.copy(
            idToken = refreshed.idToken,
            idTokenExpiresAtEpochSec = refreshed.expiresAtEpochSec,
        ),
        dir,
    )
    return refreshed.idToken
  }
}
