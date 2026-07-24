package software.medusa.flow.server

import kotlin.time.Duration

/**
 * An in-memory [GithubOutboxStore] over the state shared with [InMemoryIssuePipelineStore] (see
 * [InMemoryPipelineBackend]). Entries are enqueued by pipeline transitions; this store is the
 * read/dispatch side.
 */
class InMemoryGithubOutboxStore(
    private val backend: InMemoryPipelineBackend = InMemoryPipelineBackend(),
) : GithubOutboxStore {
  private val clock = backend.clock

  override suspend fun dueEntries(
      repoFullName: String,
  ): List<OutboxEntry> =
      synchronized(backend.lock) {
        val now = clock.now()

        backend.outboxEntries
            .filter { it.repoFullName == repoFullName && it.dispatchedAt == null }
            // Strict per-issue FIFO: only the head (lowest seq) of each issue's queue is a
            // candidate — a blocked entry holds up its own issue, never others.
            .groupBy { it.issueNumber }
            .values
            .mapNotNull { issueEntries -> issueEntries.minByOrNull { it.seq } }
            .filter { it.nextAttemptAt <= now }
            .sortedWith(compareBy({ it.issueNumber }, { it.seq }))
      }

  override suspend fun markDispatched(
      id: OutboxEntryId,
  ) {
    synchronized(backend.lock) { replace(id) { it.copy(dispatchedAt = clock.now()) } }
  }

  override suspend fun markFailed(
      id: OutboxEntryId,
      error: String,
      backoff: Duration,
  ) {
    synchronized(backend.lock) {
      replace(id) {
        it.copy(
            attempts = it.attempts + 1,
            lastError = error,
            nextAttemptAt = clock.now() + backoff,
        )
      }
    }
  }

  override suspend fun stuckEntries(
      maxAttempts: Int,
  ): List<OutboxEntry> =
      synchronized(backend.lock) {
        backend.outboxEntries.filter { it.dispatchedAt == null && it.attempts >= maxAttempts }
      }

  override suspend fun reposWithPendingEntries(): List<String> =
      synchronized(backend.lock) {
        backend.outboxEntries.filter { it.dispatchedAt == null }.map { it.repoFullName }.distinct()
      }

  private inline fun replace(
      id: OutboxEntryId,
      update: (OutboxEntry) -> OutboxEntry,
  ) {
    val index = backend.outboxEntries.indexOfFirst { it.id == id }
    if (index >= 0) backend.outboxEntries[index] = update(backend.outboxEntries[index])
  }
}
