package software.medusa.flow.cli

import com.github.ajalt.clikt.core.PrintMessage
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.runBlocking

/**
 * Resolves a valid ID token (refreshing silently if needed) and the API URL from the environment,
 * builds a [FlowApiClient], and runs [block] against it — turning the expected failures (not signed
 * in, missing config, an API rejection) into clean single-line CLI errors rather than stack traces.
 *
 * The read commands trigger a *silent* refresh via [FlowSession]; they never launch the browser
 * sign-in themselves. A missing/expired refresh token surfaces as a "run flow login" hint.
 */
internal fun <T> withFlowApiClient(
    lookup: (String) -> String? = System::getenv,
    block: suspend (FlowApiClient) -> T,
): T {
  val apiUrl =
      try {
        FlowConfig.apiUrlFromEnvironment(lookup)
      } catch (e: FlowConfig.Companion.MissingException) {
        throw PrintMessage(e.message ?: "Missing configuration.", statusCode = 1, printError = true)
      }

  val idToken =
      try {
        FlowSession.fromEnvironment(lookup = lookup).currentIdToken()
      } catch (e: FlowNotLoggedInException) {
        throw PrintMessage(e.message ?: "Not signed in.", statusCode = 1, printError = true)
      }

  val client = FlowApiClient.create(apiUrl = apiUrl, idToken = idToken)

  return try {
    runBlocking { block(client) }
  } catch (e: StatusException) {
    throw apiError(e.status)
  } catch (e: StatusRuntimeException) {
    throw apiError(e.status)
  }
}

private fun apiError(status: Status): PrintMessage {
  val message =
      when (status.code) {
        Status.Code.UNAUTHENTICATED,
        Status.Code.PERMISSION_DENIED ->
            "The Flow API rejected your identity (${status.code}). Your session may have lapsed, or " +
                "your account isn't allowed — try 'flow login' again."
        else -> "Flow API error (${status.code}): ${status.description ?: "no detail"}"
      }
  return PrintMessage(message, statusCode = 1, printError = true)
}
