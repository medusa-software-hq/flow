package software.medusa.flow.server

import java.time.Duration

/**
 * Drains the GitHub outbox for a repo: turns due [OutboxEntry] rows into GitHub API calls, in
 * strict per-issue FIFO order, marking each dispatched on success or failed (with exponential
 * backoff) on error. Invoked from reconcile — there is no background loop.
 *
 * Ordering and isolation come from [GithubOutboxStore.dueEntries], which returns only the head of
 * each issue's queue: a failing entry backs off and blocks *its own* issue until it succeeds, while
 * other issues (and repos) keep flowing.
 *
 * Idempotency: every action is safe to replay except `POST_COMMENT`. A crash between the GitHub
 * call succeeding and [GithubOutboxStore.markDispatched] committing can therefore duplicate an
 * annotation comment on the next drain — an accepted trade-off (comments are annotations, not
 * state; labels and close are idempotent).
 */
class OutboxDispatcher(
    private val outboxStore: GithubOutboxStore,
    private val gitHubIssueClient: GitHubIssueClient,
    private val baseBackoff: Duration = Duration.ofSeconds(30),
    private val maxBackoff: Duration = Duration.ofMinutes(30),
) {
  /**
   * Executes every currently-due entry in [repoFullName]. Returns how many were dispatched
   * successfully (for the reconcile summary). Ensures the `flow:*` label definitions exist first.
   */
  suspend fun drain(
      repoFullName: String,
  ): Int {
    val dueEntries = outboxStore.dueEntries(repoFullName)
    if (dueEntries.isEmpty()) return 0

    // Best-effort, first-use label setup. A transient failure here must not block the whole drain:
    // the labels may already exist from a prior run, and any add-label that genuinely can't find
    // its label will fail and back off on its own below.
    try {
      gitHubIssueClient.ensureLabelsExist(repoFullName)
    } catch (_: Exception) {
      // Ignored — see above.
    }

    var dispatched = 0
    for (entry in dueEntries) {
      try {
        execute(entry)
        outboxStore.markDispatched(entry.id)
        dispatched += 1
      } catch (e: Exception) {
        // Blocks only this issue's queue (via next_attempt_at); other issues in `dueEntries` keep
        // going. Never dropped — stuckEntries surfaces it after enough attempts.
        outboxStore.markFailed(
            entry.id,
            error = e.message ?: e.toString(),
            backoff = backoffFor(entry),
        )
      }
    }
    return dispatched
  }

  private suspend fun execute(
      entry: OutboxEntry,
  ) {
    when (entry.action) {
      OutboxAction.AddLabel ->
          gitHubIssueClient.addLabel(
              entry.repoFullName,
              entry.issueNumber,
              OutboxPayloads.readLabel(entry.payload),
          )
      OutboxAction.RemoveLabel ->
          gitHubIssueClient.removeLabel(
              entry.repoFullName,
              entry.issueNumber,
              OutboxPayloads.readLabel(entry.payload),
          )
      OutboxAction.PostComment ->
          gitHubIssueClient.postComment(
              entry.repoFullName,
              entry.issueNumber,
              OutboxPayloads.readComment(entry.payload),
          )
      OutboxAction.CloseIssue -> gitHubIssueClient.closeIssue(entry.repoFullName, entry.issueNumber)
    }
  }

  /** Exponential backoff in the entry's current attempt count, capped at [maxBackoff]. */
  private fun backoffFor(
      entry: OutboxEntry,
  ): Duration {
    val multiplier = 1L shl entry.attempts.coerceAtMost(20) // 2^attempts, guarded against overflow
    val scaled = baseBackoff.multipliedBy(multiplier)
    return if (scaled > maxBackoff) maxBackoff else scaled
  }
}
