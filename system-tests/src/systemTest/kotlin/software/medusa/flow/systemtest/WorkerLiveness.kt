package software.medusa.flow.systemtest

import io.grpc.StatusException
import java.time.Duration
import java.time.Instant
import software.medusa.flow.v1.WorkerInfo
import software.medusa.flow.v1.listWorkersRequest

/**
 * Distinct signal that staging has no live worker (imported from B6). Thrown by the loop-tier
 * preflight so a worker outage reads as "staging worker down" in the gate summary — a specific
 * infrastructure failure to be handled by an admin restarting the worker — never a generic test
 * timeout mistaken for a code regression.
 */
class StagingWorkerDownException(
    message: String,
) : Exception(message)

/**
 * The worker-liveness preflight (story 01): a first-class check, run before any worker-dependent
 * (loop-tier) test, that a worker has registered with the control-plane fleet registry recently
 * enough to be considered alive.
 *
 * Liveness is registration recency: the worker re-registers every ~15s
 * ([software.medusa.flow.worker.WrkRegistrationLoop]), so [freshnessBound] is a small multiple of
 * that — long enough that one missed registration doesn't flap the gate, short enough that a
 * stopped worker is caught within a couple of minutes.
 */
object WorkerLiveness {
  /** A worker seen within this window counts as alive. Comfortably above the ~15s re-register. */
  val freshnessBound: Duration = Duration.ofSeconds(90)

  /**
   * Returns the freshest live worker, or throws [StagingWorkerDownException] with a diagnostic
   * (what the registry held and how stale each entry was). [now] is injectable for tests.
   */
  suspend fun requireLiveWorker(
      clients: StagingClients,
      log: (String) -> Unit = ::println,
      now: () -> Instant = Instant::now,
  ): WorkerInfo {
    val workers =
        try {
          clients.workerService.listWorkers(listWorkersRequest {}).workersList
        } catch (e: StatusException) {
          throw StagingWorkerDownException(
              "staging worker down: could not read the worker fleet registry (${e.status}). " +
                  "The API is reachable but ListWorkers failed — check the deploy.",
          )
        }

    val nowInstant = now()
    val fresh =
        workers
            .map { it to Duration.between(it.lastSeenAt.toInstant(), nowInstant) }
            .filter { (_, age) -> age <= freshnessBound }
            .maxByOrNull { (worker, _) -> worker.lastSeenAt.toInstant() }
            ?.first

    if (fresh == null) {
      throw StagingWorkerDownException(buildDiagnostic(workers, nowInstant))
    }

    log(
        "staging worker live: id=${fresh.workerId}, version=${fresh.workerVersion.ifEmpty { "unknown" }}, " +
            "digest=${fresh.imageDigest.ifEmpty { "n/a" }}, " +
            "last seen ${Duration.between(fresh.lastSeenAt.toInstant(), nowInstant).toSeconds()}s ago",
    )
    return fresh
  }

  private fun buildDiagnostic(
      workers: List<WorkerInfo>,
      now: Instant,
  ): String =
      if (workers.isEmpty()) {
        "staging worker down: the worker fleet registry is empty — no worker has ever registered. " +
            "An admin must start the staging worker (ms-workload worker run --profile flow-worker-staging)."
      } else {
        val ages =
            workers.joinToString("; ") {
              "${it.workerId} last seen ${Duration.between(it.lastSeenAt.toInstant(), now).toSeconds()}s ago"
            }
        "staging worker down: no worker registered within ${freshnessBound.toSeconds()}s " +
            "(freshness bound). Known workers: $ages. The admin worker is stopped or wedged."
      }
}

private fun com.google.protobuf.Timestamp.toInstant(): Instant =
    Instant.ofEpochSecond(seconds, nanos.toLong())
