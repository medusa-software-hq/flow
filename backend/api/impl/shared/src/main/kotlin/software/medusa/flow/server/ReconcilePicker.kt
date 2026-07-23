package software.medusa.flow.server

import org.slf4j.LoggerFactory

/**
 * The reconcile pick phase (story 07): if the repo is free, start work on the oldest unblocked
 * `ready` issue.
 *
 * The repo mutex ([IssuePipelineStore.isRepoBusy], which counts a non-cleared `FAILED` row as busy
 * — maximum caution) gates picking: one failure stops the repo until a human clears it. Candidates
 * come from [GitHubCandidateClient] (already filtered to open, `ready`, zero-open-blockers); we
 * drop any that already have a non-cleared pipeline row, take the oldest, create an issue's
 * session, and start the pipeline.
 *
 * Atomicity: the session and the `pick` are two store calls, made safe by the reconciler's per-repo
 * lock ([RepoLock]) — no other pick for this repo can interleave in M2's single-instance control
 * plane. (The `issue_pipelines` unique indexes are the SQL-level backstop; multi-instance atomicity
 * via a single transaction is deferred with the advisory lock.)
 */
class ReconcilePicker(
    private val pipelineStore: IssuePipelineStore,
    private val sessionStore: SessionStore,
    private val candidateClient: GitHubCandidateClient,
) : PipelinePicker {
  private val log = LoggerFactory.getLogger(ReconcilePicker::class.java)

  override suspend fun pick(
      repoFullName: String,
  ): Int {
    if (pipelineStore.isRepoBusy(repoFullName)) return 0

    val candidates = candidateClient.findReadyCandidates(repoFullName)
    if (candidates.isEmpty()) return 0

    // Never re-pick an issue that already has a non-cleared pipeline row (DONE, or an uncleared
    // FAILED — though the latter would have made the repo busy above).
    val takenIssueNumbers =
        pipelineStore
            .list(repoFullName)
            .filter { it.clearedAt == null }
            .map { it.issueNumber }
            .toSet()

    val chosen =
        candidates.filter { it.number !in takenIssueNumbers }.minByOrNull { it.createdAt }
            ?: return 0

    // Dual-engine fan-out (M6): every picked issue runs two sessions in parallel — a primary Claude
    // session that drives the pipeline (observed, merge-gated, closes the issue) and a built-in
    // "shadow" session for comparison that opens its own PR but is never observed. The engine is no
    // longer read from a `flow:engine=` label; both always run.
    // One job holds both engine sessions, so a worker claims and runs them together (in parallel).
    val (primary, shadow) =
        sessionStore.createJob(
            repoFullName = repoFullName,
            taskMarkdown = taskMarkdownFor(chosen),
            createdBy = reconcilerAuthor,
            engines = listOf(Engine.Claude, Engine.Builtin),
        )

    return when (
        val result =
            pipelineStore.pick(
                repoFullName = repoFullName,
                issueNumber = chosen.number,
                issueTitle = chosen.title,
                issueUrl = chosen.url,
                sessionId = primary.id,
                shadowSessionId = shadow.id,
            )
    ) {
      is PickResult.Picked -> {
        log.info(
            "picked {}#{} → pipeline {} (primary {}, shadow {})",
            repoFullName,
            chosen.number,
            result.pipeline.id.id,
            primary.id.id,
            shadow.id.id,
        )
        1
      }
      PickResult.RepoBusy -> {
        // Unreachable under the per-repo lock. If the invariant ever breaks, log loudly so the
        // orphaned sessions are noticed rather than silently claimed by a worker.
        log.error(
            "pick raced on {}#{}: repo became busy after the free check; sessions {}/{} orphaned",
            repoFullName,
            chosen.number,
            primary.id.id,
            shadow.id.id,
        )
        0
      }
    }
  }

  /**
   * Task Markdown = issue title as an H1 + the issue body verbatim. Comments are excluded in M2.
   */
  private fun taskMarkdownFor(
      issue: CandidateIssue,
  ): String = "# ${issue.title}\n\n${issue.body}"

  private companion object {
    /** `created_by` marker for reconciler-created sessions (no human caller in this path). */
    private const val reconcilerAuthor = "flow-reconciler"
  }
}
