package software.medusa.flow.worker

import io.grpc.Status
import io.grpc.StatusException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import software.medusa.flow.v1.Session

/**
 * Claim → process → repeat. Strictly one session at a time. Cooperatively cancellable at its
 * `delay` suspension points, so a caller can stop it cleanly (e.g. on SIGINT) via coroutine
 * cancellation.
 */
class WrkPollLoop(
    private val apiClient: WrkApiClient,
    private val sessionProcessor: WrkSessionProcessor,
    private val emptyPollDelayMillis: Long = 5_000,
    private val log: (String) -> Unit = ::println,
) {
  suspend fun run() {
    log("Poll loop started; claiming sessions (empty-poll interval ${emptyPollDelayMillis}ms)")
    // An empty claim is the common steady state, so we don't log every 5s poll. But we must log the
    // *first* empty claim: otherwise a worker that's connected, authenticated, and simply finds no
    // pending session is indistinguishable from one hung or rejected on its first RPC — the exact
    // ambiguity that made the first hosted-worker run (M4 Path B) impossible to diagnose from logs.
    var idleAnnounced = false
    while (coroutineContext.isActive) {
      val session =
          try {
            apiClient.claimNextSession()
          } catch (e: StatusException) {
            log("claimNextSession failed (${e.status}), retrying after a delay")
            delay(emptyPollDelayMillis)
            continue
          }

      if (session == null) {
        if (!idleAnnounced) {
          log("No session available yet; polling every ${emptyPollDelayMillis}ms until one appears")
          idleAnnounced = true
        }
        delay(emptyPollDelayMillis)
        continue
      }
      idleAnnounced = false

      log("Claimed session ${session.id}")
      processClaimedSession(session)
    }
  }

  private suspend fun processClaimedSession(session: Session) {
    try {
      sessionProcessor.process(session = session, apiClient = apiClient)
    } catch (e: StatusException) {
      if (e.status.code == Status.Code.FAILED_PRECONDITION) {
        log("Session ${session.id}: control plane reports it's no longer RUNNING, abandoning")
      } else {
        log("Session ${session.id}: control-plane RPC failed (${e.status}), abandoning")
      }
    } catch (e: Exception) {
      log("Session ${session.id}: processing failed unexpectedly ($e), attempting to fail it")
      tryFailSession(session.id, e)
    }
  }

  private suspend fun tryFailSession(
      sessionId: String,
      cause: Exception,
  ) {
    try {
      apiClient.failSession(
          sessionId = sessionId,
          failureSummary = "Worker encountered an unexpected error:\n\n```\n$cause\n```",
      )
    } catch (e: Exception) {
      log("Session $sessionId: failSession itself failed ($e), giving up locally")
    }
  }
}
