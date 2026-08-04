package software.medusa.flow.server

import kotlin.time.Clock

/**
 * An in-memory [WorkerStore] for tests and local runs, following [InMemorySessionStore]. Not
 * durable; a [Clock] is injectable so last-seen recency is deterministic in tests.
 */
class InMemoryWorkerStore(
    private val clock: Clock = Clock.System,
) : WorkerStore {
  private val lock = Any()
  private val workersById = HashMap<String, RegisteredWorker>()

  override suspend fun register(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
  ) {
    synchronized(lock) {
      val now = clock.now()
      val existing = workersById[workerId]
      workersById[workerId] =
          RegisteredWorker(
              workerId = workerId,
              workerVersion = workerVersion,
              imageDigest = imageDigest,
              firstSeenAt = existing?.firstSeenAt ?: now,
              lastSeenAt = now,
          )
    }
  }

  override suspend fun list(): List<RegisteredWorker> =
      synchronized(lock) { workersById.values.sortedByDescending { it.lastSeenAt } }
}
