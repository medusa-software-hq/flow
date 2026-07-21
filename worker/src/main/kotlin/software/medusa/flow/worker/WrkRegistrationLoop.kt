package software.medusa.flow.worker

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import software.medusa.flow.v1.Engine

/**
 * Periodically re-registers this worker with the control-plane fleet registry (M5), so the
 * system-test gate can tell "a worker is alive" from "the worker is down" (a distinct preflight
 * failure) and print the version it tested against.
 *
 * Runs in its own coroutine alongside the [WrkPollLoop], on a cadence well under the gate's
 * liveness freshness bound, so the registration stays fresh even while a long session is running
 * (the poll loop is busy then, but this loop keeps ticking). Registration failures are logged and
 * retried, never fatal — a transient control-plane blip must not take the worker down.
 * Cooperatively cancellable at its `delay`.
 */
class WrkRegistrationLoop(
    private val apiClient: WrkApiClient,
    private val identity: WrkWorkerIdentity,
    private val supportedEngines: List<Engine>,
    private val intervalMillis: Long = defaultIntervalMillis,
    private val log: (String) -> Unit = ::println,
) {
  companion object {
    /**
     * 15s: comfortably under the system-test preflight's liveness freshness bound (a small multiple
     * of this), so a single missed registration doesn't flap the gate.
     */
    const val defaultIntervalMillis: Long = 15_000
  }

  suspend fun run() {
    log("Registration loop started (worker_id=${identity.workerId}, every ${intervalMillis}ms)")
    while (coroutineContext.isActive) {
      registerOnce()
      delay(intervalMillis)
    }
  }

  private suspend fun registerOnce() {
    try {
      apiClient.registerWorker(
          workerId = identity.workerId,
          workerVersion = identity.workerVersion,
          imageDigest = identity.imageDigest,
          supportedEngines = supportedEngines,
      )
    } catch (e: CancellationException) {
      // Shutdown — let cancellation propagate so the loop stops cleanly.
      throw e
    } catch (e: Exception) {
      // Registration is best-effort liveness reporting: a failure (transport blip, control-plane
      // rejection) must never take the worker down or interrupt the session it's running. Log and
      // retry on the next tick.
      log("registerWorker failed ($e); will retry in ${intervalMillis}ms")
    }
  }
}
