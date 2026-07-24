package software.medusa.flow.server

import kotlin.time.Clock
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Github_outbox

/**
 * A Postgres-backed [GithubOutboxStore] over the Flyway-owned schema, sharing [database] with
 * [PostgresIssuePipelineStore] so it reads exactly the entries those transitions enqueue.
 */
class PostgresGithubOutboxStore(
    private val database: FlowDatabase,
    private val clock: Clock = Clock.System,
) : GithubOutboxStore {
  private val queries = database.githubOutboxQueries

  override suspend fun dueEntries(
      repoFullName: String,
  ): List<OutboxEntry> =
      withContext(Dispatchers.IO) {
        queries
            .dueEntries(repo_full_name = repoFullName, now = clock.now().toOffsetDateTime())
            .executeAsList()
            .map { it.toDomain() }
      }

  override suspend fun markDispatched(
      id: OutboxEntryId,
  ) {
    withContext(Dispatchers.IO) {
      queries.markDispatched(now = clock.now().toOffsetDateTime(), id = id.id)
    }
  }

  override suspend fun markFailed(
      id: OutboxEntryId,
      error: String,
      backoff: Duration,
  ) {
    withContext(Dispatchers.IO) {
      queries.markFailed(
          last_error = error,
          next_attempt_at = (clock.now() + backoff).toOffsetDateTime(),
          id = id.id,
      )
    }
  }

  override suspend fun stuckEntries(
      maxAttempts: Int,
  ): List<OutboxEntry> =
      withContext(Dispatchers.IO) {
        queries.stuckEntries(maxAttempts).executeAsList().map { it.toDomain() }
      }

  override suspend fun reposWithPendingEntries(): List<String> =
      withContext(Dispatchers.IO) { queries.reposWithPendingEntries().executeAsList() }

  private fun Github_outbox.toDomain(): OutboxEntry =
      OutboxEntry(
          id = OutboxEntryId(id),
          repoFullName = repo_full_name,
          issueNumber = issue_number,
          seq = seq,
          action = parseOutboxAction(action),
          payload = payload,
          createdAt = created_at.toKotlinInstant(),
          dispatchedAt = dispatched_at?.toKotlinInstant(),
          attempts = attempts,
          lastError = last_error,
          nextAttemptAt = next_attempt_at.toKotlinInstant(),
      )
}
