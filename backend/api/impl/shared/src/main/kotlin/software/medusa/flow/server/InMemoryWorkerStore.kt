package software.medusa.flow.server

import java.time.Clock

/**
 * An in-memory [WorkerStore] for tests and local runs, following [InMemorySessionStore]. Not
 * durable; a [Clock] is injectable so last-seen recency is deterministic in tests.
 */
class InMemoryWorkerStore(
    private val clock: Clock = Clock.systemUTC(),
) : WorkerStore {
  private val lock = Any()
  private val workersById = HashMap<String, RegisteredWorker>()

  override suspend fun register(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
      supportedEngines: List<Engine>,
  ) {
    synchronized(lock) {
      val now = clock.instant()
      val existing = workersById[workerId]
      workersById[workerId] =
          RegisteredWorker(
              workerId = workerId,
              workerVersion = workerVersion,
              imageDigest = imageDigest,
              supportedEngines = supportedEngines,
              firstSeenAt = existing?.firstSeenAt ?: now,
              lastSeenAt = now,
          )
    }
  }

  override suspend fun list(): List<RegisteredWorker> =
      synchronized(lock) { workersById.values.sortedByDescending { it.lastSeenAt } }
}
