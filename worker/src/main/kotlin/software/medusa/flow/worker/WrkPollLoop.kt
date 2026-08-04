package software.medusa.flow.worker

import io.grpc.Status
import io.grpc.StatusException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.flow.v1.Session

/**
 * Claim → process → repeat. Claims a whole *job* (one unit of work) and runs its sessions — both
 * engines of a reconciled issue — in parallel, one job at a time.
 *
 * Two distinct ways to stop it:
 * - **Cordon** ([cordon]): stop claiming new work, but let whatever is currently in flight run to
 *   completion. This is the drain contract SIGTERM maps to (see `WorkCommand`) — a graceful restart
 *   must not abandon a session that's already most of the way to a PR.
 * - **Cancellation**: cancelling the coroutine running [run] (e.g. its parent `Job`) still tears
 *   everything down immediately, in-flight session included. Reserved for the bounded
 *   drain-deadline exception, not the everyday stop path.
 */
class WrkPollLoop(
    private val apiClient: WrkApiClient,
    private val sessionProcessor: WrkSessionProcessor,
    private val emptyPollDelayMillis: Long = 5_000,
    private val log: (String) -> Unit = ::println,
) {
  private val cordoned = CompletableDeferred<Unit>()

  // Only ever written from the single coroutine running `run`; read from whichever thread calls
  // `inFlightSessionIds`/`forceFailInFlight` (the shutdown-hook thread) — hence @Volatile.
  @Volatile private var currentlyProcessing: List<String> = emptyList()

  /**
   * Stops claiming new sessions: the current `run` iteration finishes whatever it already claimed,
   * then exits instead of looping again. Idempotent, and safe to call from any thread (e.g. a JVM
   * shutdown hook).
   */
  fun cordon() {
    cordoned.complete(Unit)
  }

  /** Session ids currently being processed — empty if the loop is idle. */
  fun inFlightSessionIds(): List<String> = currentlyProcessing

  /**
   * Force-fails every currently in-flight session with [reason], best-effort, flagged as a worker
   * death so the control plane requeues it for a fresh attempt (bounded retry) instead of
   * abandoning it. Meant for the bounded drain-deadline exception: the caller is about to cancel
   * this loop's job outright (killing the engine mid-run) and wants the affected session tagged
   * explicitly rather than left for generic heartbeat expiry to explain later.
   */
  suspend fun forceFailInFlight(reason: String) {
    for (sessionId in currentlyProcessing) {
      try {
        apiClient.failSession(sessionId = sessionId, failureSummary = reason, workerDeath = true)
      } catch (e: Exception) {
        log("Session $sessionId: force-fail at drain deadline failed ($e)")
      }
    }
  }

  suspend fun run() {
    log("Poll loop started; claiming sessions (empty-poll interval ${emptyPollDelayMillis}ms)")
    // An empty claim is the common steady state, so we don't log every 5s poll. But we must log the
    // *first* empty claim: otherwise a worker that's connected, authenticated, and simply finds no
    // pending session is indistinguishable from one hung or rejected on its first RPC — the exact
    // ambiguity that made the first hosted-worker run (M4 Path B) impossible to diagnose from logs.
    var idleAnnounced = false
    while (coroutineContext.isActive && !cordoned.isCompleted) {
      val sessions =
          try {
            apiClient.claimNextJob()
          } catch (e: StatusException) {
            log("claimNextJob failed (${e.status}), retrying after a delay")
            waitOrCordon(emptyPollDelayMillis)
            continue
          }

      if (sessions.isEmpty()) {
        if (!idleAnnounced) {
          log("No session available yet; polling every ${emptyPollDelayMillis}ms until one appears")
          idleAnnounced = true
        }
        waitOrCordon(emptyPollDelayMillis)
        continue
      }
      idleAnnounced = false

      log("Claimed job of ${sessions.size} session(s): ${sessions.joinToString { it.id }}")
      currentlyProcessing = sessions.map { it.id }
      try {
        // Run every engine of the job in parallel — one issue, both engines at once, under one
        // worker.
        // processClaimedSession swallows its own failures, so one engine failing never cancels the
        // other.
        coroutineScope {
          sessions.map { session -> async { processClaimedSession(session) } }.awaitAll()
        }
      } finally {
        currentlyProcessing = emptyList()
      }
    }
    if (cordoned.isCompleted) {
      log("Cordoned and idle; poll loop exiting cleanly")
    }
  }

  /** Waits up to [millis] for a poll retry, but returns as soon as [cordon] is called. */
  private suspend fun waitOrCordon(millis: Long) {
    withTimeoutOrNull(millis) { cordoned.await() }
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
