package software.medusa.flow.server

import java.time.Instant

@JvmInline
value class OutboxEntryId(
    val id: String,
)

/**
 * A GitHub side effect of a pipeline transition, executed with the App installation token. All are
 * idempotent except [PostComment] — a crash between the API call and marking the entry dispatched
 * may duplicate an annotation comment (an accepted trade-off; comments are annotations, not state).
 */
enum class OutboxAction {
  AddLabel,
  RemoveLabel,
  PostComment,
  /** The graph-advancing action; deliberately rides the same mechanism. Idempotent. */
  CloseIssue,
}

/**
 * A queued GitHub side effect. [payload] is action-specific JSON (a label name, a comment body, or
 * empty for close).
 */
data class OutboxEntry(
    val id: OutboxEntryId,
    val repoFullName: String,
    val issueNumber: Int,
    val seq: Int,
    val action: OutboxAction,
    val payload: String,
    val createdAt: Instant,
    val dispatchedAt: Instant?,
    val attempts: Int,
    val lastError: String?,
    val nextAttemptAt: Instant,
)

/**
 * The GitHub outbox: side effects written transactionally with pipeline transitions, then drained
 * idempotently. Draining happens at the start of every reconcile (no background loop), mirroring
 * M1's lazy-expiry pattern.
 *
 * Enqueueing is not on this interface: entries are written by [IssuePipelineStore] transitions
 * inside their own transaction, so an entry can never exist without its state change (or vice
 * versa).
 */
interface GithubOutboxStore {
  /**
   * The undispatched entries in [repoFullName] that are due now (`next_attempt_at <= now`),
   * strictly FIFO per issue: only the head (lowest `seq`) of each issue's queue, so a failing entry
   * blocks only its own issue — never other issues or repos.
   */
  suspend fun dueEntries(
      repoFullName: String,
  ): List<OutboxEntry>

  /** Marks an entry successfully delivered. */
  suspend fun markDispatched(
      id: OutboxEntryId,
  )

  /**
   * Records a failed delivery: increments `attempts`, stores [error], and pushes `next_attempt_at`
   * out by [backoff] so the entry (and its issue's queue behind it) is retried later.
   */
  suspend fun markFailed(
      id: OutboxEntryId,
      error: String,
      backoff: java.time.Duration,
  )

  /**
   * Entries that have failed at least [maxAttempts] times and are still undispatched — surfaced in
   * the web UI as a GitHub sync problem. Never silently dropped.
   */
  suspend fun stuckEntries(
      maxAttempts: Int,
  ): List<OutboxEntry>

  companion object {
    /** After this many failed attempts an entry is considered stuck (default). */
    const val defaultStuckAttempts = 5
  }
}
