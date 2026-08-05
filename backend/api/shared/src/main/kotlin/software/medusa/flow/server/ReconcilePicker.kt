package software.medusa.flow.server

import org.slf4j.LoggerFactory

/**
 * The reconcile pick phase (story 07): if the repo is free, start work on the highest-priority
 * unblocked `ready` issue (oldest first within a priority tier — see [IssuePriority]).
 *
 * The repo mutex ([IssuePipelineStore.isRepoBusy], true for the pre-merge work phase —
 * `IN_PROGRESS` / `PR_OPEN` — plus a non-cleared `FAILED` row; maximum caution) gates picking: it
 * releases as soon as the current pipeline's PR merges (`AWAITING_MERGE_CHECKS` doesn't count), so
 * the next issue starts while post-merge checks are still watched, but one post-merge failure stops
 * the repo until a human clears it. Candidates come from [GitHubCandidateClient] (already filtered
 * to open, `ready`, zero-open-blockers); we drop any that already have a non-cleared pipeline row,
 * take the highest-priority/oldest, create an issue's session, and start the pipeline.
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
        candidates
            .filter { it.number !in takenIssueNumbers }
            .minWithOrNull(compareBy({ IssuePriority.of(it.priorityField).rank }, { it.createdAt }))
            ?: return 0

    // Every picked issue runs a primary Claude session that drives the pipeline (observed,
    // merge-gated, closes the issue). The dual-engine fan-out (M6) also spawned a built-in "shadow"
    // session for comparison, but the built-in engine proved too unreliable to run unattended — it
    // ~always failed, wasting worker time/budget and cluttering the session list. The shadow
    // fan-out
    // is disabled (not removed: [SessionStore.createJob] and [IssuePipelineStore.shadowSessionId]
    // stay in place for when a future leader/assistant engine replaces built-in as the shadow).
    val (primary) =
        sessionStore.createJob(
            repoFullName = repoFullName,
            taskMarkdown = taskMarkdownFor(chosen),
            createdBy = reconcilerAuthor,
            engines = listOf(Engine.Claude),
        )

    return when (
        val result =
            pipelineStore.pick(
                repoFullName = repoFullName,
                issueNumber = chosen.number,
                issueTitle = chosen.title,
                issueUrl = chosen.url,
                sessionId = primary.id,
                shadowSessionId = null,
            )
    ) {
      is PickResult.Picked -> {
        log.info(
            "picked {}#{} → pipeline {} (primary {})",
            repoFullName,
            chosen.number,
            result.pipeline.id.id,
            primary.id.id,
        )
        1
      }
      PickResult.RepoBusy -> {
        // Unreachable under the per-repo lock. If the invariant ever breaks, log loudly so the
        // orphaned session is noticed rather than silently claimed by a worker.
        log.error(
            "pick raced on {}#{}: repo became busy after the free check; session {} orphaned",
            repoFullName,
            chosen.number,
            primary.id.id,
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
