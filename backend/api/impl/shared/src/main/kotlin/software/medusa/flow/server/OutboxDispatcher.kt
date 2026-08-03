package software.medusa.flow.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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
 *
 * This is also Flow's repo-onboarding path: [drain] ensures the `flow:` label manifest exists
 * *before* looking for outbox work, not only as a side effect of having some, so a repo with none
 * of the `flow:` labels gets them provisioned the first time reconcile ever touches it — including
 * `flow:ready`, before any issue is ever hand-labeled with it.
 */
class OutboxDispatcher(
    private val outboxStore: GithubOutboxStore,
    private val gitHubIssueClient: GitHubIssueClient,
    private val baseBackoff: Duration = 30.seconds,
    private val maxBackoff: Duration = 30.minutes,
) {
  // Repos whose labels have been successfully ensured this process's lifetime — avoids re-hitting
  // GitHub on every drain (most of which find no due work) once onboarding has succeeded once.
  // Idempotent either way; this is purely a call-volume optimization. A restart re-ensures once per
  // repo, which is harmless (create-if-absent).
  private val labelsEnsuredRepos = ConcurrentHashMap.newKeySet<String>()

  /**
   * Executes every currently-due entry in [repoFullName]. Returns how many were dispatched
   * successfully (for the reconcile summary). Ensures the `flow:` label manifest exists first,
   * regardless of whether there's any outbox work.
   */
  suspend fun drain(
      repoFullName: String,
  ): Int {
    ensureLabelsOnce(repoFullName)

    val dueEntries = outboxStore.dueEntries(repoFullName)
    if (dueEntries.isEmpty()) return 0

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

  /**
   * Best-effort, first-use label provisioning, cached per repo so a steady stream of drains against
   * a repo with nothing due doesn't re-hit GitHub every time. A transient failure isn't cached, so
   * the next [drain] call retries — it must never block draining otherwise-ready outbox entries.
   */
  private suspend fun ensureLabelsOnce(
      repoFullName: String,
  ) {
    if (repoFullName in labelsEnsuredRepos) return
    try {
      gitHubIssueClient.ensureLabelsExist(repoFullName)
      labelsEnsuredRepos += repoFullName
    } catch (_: Exception) {
      // Ignored — see above; retried on the next drain() for this repo.
    }
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
    val multiplier = 1 shl entry.attempts.coerceAtMost(20) // 2^attempts, guarded against overflow
    val scaled = baseBackoff * multiplier
    return if (scaled > maxBackoff) maxBackoff else scaled
  }
}
