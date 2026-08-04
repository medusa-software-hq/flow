package software.medusa.flow.server

import java.util.UUID
import kotlin.time.Clock

/**
 * Shared in-memory state for [InMemoryIssuePipelineStore] and [InMemoryGithubOutboxStore]. Both
 * synchronize on this object's [lock], so a pipeline transition and the outbox entries it enqueues
 * are applied atomically — the in-memory stand-in for the single Postgres transaction the two
 * Postgres stores share.
 *
 * A [Clock] is injectable so timestamps and ordering are deterministic in tests.
 */
class InMemoryPipelineBackend(
    val clock: Clock = Clock.System,
) {
  val lock = Any()

  // Insertion-ordered so rows sharing a created_at keep a deterministic order.
  val pipelinesById = LinkedHashMap<IssuePipelineId, IssuePipeline>()

  // Insertion-ordered; per-(repo, issue) FIFO is derived from `seq`.
  val outboxEntries = mutableListOf<OutboxEntry>()

  private val nextSeqByIssue = HashMap<Pair<String, Int>, Int>()

  /**
   * Appends an outbox entry, assigning the next per-`(repoFullName, issueNumber)` sequence number.
   * Must be called while holding [lock] (i.e. from within a store operation).
   */
  fun enqueueLocked(
      repoFullName: String,
      issueNumber: Int,
      action: OutboxAction,
      payload: String,
  ) {
    val issueKey = repoFullName to issueNumber
    val seq = nextSeqByIssue.getOrDefault(issueKey, 0) + 1
    nextSeqByIssue[issueKey] = seq

    val now = clock.now()

    outboxEntries +=
        OutboxEntry(
            id = OutboxEntryId(UUID.randomUUID().toString()),
            repoFullName = repoFullName,
            issueNumber = issueNumber,
            seq = seq,
            action = action,
            payload = payload,
            createdAt = now,
            dispatchedAt = null,
            attempts = 0,
            lastError = null,
            nextAttemptAt = now,
        )
  }
}
