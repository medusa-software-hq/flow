package software.medusa.flow.server

import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Workers

/**
 * A Postgres-backed [WorkerStore] (SQLDelight queries over the Flyway-owned schema), following the
 * [PostgresSessionStore] pattern. [clock] is injectable so both implementations stamp last-seen
 * from the same source and behave identically.
 */
class PostgresWorkerStore(
    private val database: FlowDatabase,
    private val clock: Clock = Clock.systemUTC(),
) : WorkerStore {
  private val queries = database.workerQueries

  override suspend fun register(
      workerId: String,
      workerVersion: String,
      imageDigest: String,
      supportedEngines: List<Engine>,
  ) {
    withContext(Dispatchers.IO) {
      queries.upsertWorker(
          worker_id = workerId,
          worker_version = workerVersion,
          image_digest = imageDigest,
          supported_engines = supportedEngines.toDbValue(),
          now = clock.instant().toOffsetDateTime(),
      )
    }
  }

  override suspend fun list(): List<RegisteredWorker> =
      withContext(Dispatchers.IO) { queries.listWorkers().executeAsList().map { it.toDomain() } }

  private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

  private fun Workers.toDomain(): RegisteredWorker =
      RegisteredWorker(
          workerId = worker_id,
          workerVersion = worker_version,
          imageDigest = image_digest,
          supportedEngines = engineListFromDbValue(supported_engines),
          firstSeenAt = first_seen_at.toInstant(),
          lastSeenAt = last_seen_at.toInstant(),
      )
}
