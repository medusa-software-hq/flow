package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

/**
 * The reconcile observe phase (story 06): advances live pipeline rows from GitHub reality.
 *
 * - `PR_OPEN`: PR merged → `AWAITING_MERGE_CHECKS` (records the merge commit); closed-unmerged →
 *   `FAILED`.
 * - `AWAITING_MERGE_CHECKS`: evaluate the merge gate on the merge commit — green → `DONE` (close +
 *   annotate), red → `FAILED`, pending → leave, no-runs-past-grace → vacuous `DONE`.
 *
 * Every transition goes through the guarded [IssuePipelineStore] methods, so the
 * label/comment/close outbox entries ride the same transaction automatically; the reconciler's
 * drain phase flushes them.
 *
 * Idempotent: with no GitHub change, every pipeline stays put and `observe` returns 0.
 */
class ReconcileObserver(
    private val pipelineStore: IssuePipelineStore,
    private val sessionStore: SessionStore,
    private val prClient: GitHubPrClient,
    private val clock: Clock = Clock.systemUTC(),
    private val noRunsGracePeriod: Duration = defaultNoRunsGracePeriod,
) : PipelineObserver {
  companion object {
    /** ~two scheduler ticks — long enough not to race Actions startup after a merge. */
    val defaultNoRunsGracePeriod: Duration = Duration.ofMinutes(6)
  }

  private val log = LoggerFactory.getLogger(ReconcileObserver::class.java)

  override suspend fun observe(
      repoFullName: String,
  ): Int {
    val observable =
        pipelineStore.list(repoFullName).filter {
          it.state == IssuePipelineState.InProgress ||
              it.state == IssuePipelineState.PrOpen ||
              it.state == IssuePipelineState.AwaitingMergeChecks
        }

    var advanced = 0
    for (pipeline in observable) {
      val transition =
          when (pipeline.state) {
            IssuePipelineState.InProgress -> observeInProgress(pipeline)
            IssuePipelineState.PrOpen -> observePrOpen(pipeline)
            IssuePipelineState.AwaitingMergeChecks -> observeAwaitingMergeChecks(pipeline)
            else -> null
          }
      if (transition is PipelineTransition.Applied) advanced += 1
    }
    return advanced
  }

  /**
   * Backstop for the session-driven transitions ([WorkerServiceImpl]): if the linked session
   * already reached a terminal state but the pipeline is still `IN_PROGRESS` — e.g. the worker was
   * lost and lazy heartbeat expiry failed the session, so no `FailSession` RPC ran — converge here.
   * Idempotent: if the RPC already advanced the pipeline, it isn't `IN_PROGRESS` and is skipped.
   */
  private suspend fun observeInProgress(
      pipeline: IssuePipeline,
  ): PipelineTransition? {
    val sessionId = pipeline.sessionId ?: return null
    val session = sessionStore.get(sessionId, afterSeq = 0)?.session ?: return null

    return when (session.state) {
      SessionState.Completed ->
          pipelineStore.markPrOpen(
              pipeline.id,
              prNumber = session.prUrl?.let(::parsePrNumber) ?: 0,
              prUrl = session.prUrl.orEmpty(),
          )
      SessionState.Failed ->
          pipelineStore.markFailed(
              pipeline.id,
              failureSummary =
                  "The Flow session for this issue failed:\n\n" +
                      "${session.failureSummary ?: "unknown error"}\n\n" +
                      "Clear this pipeline to let Flow try the issue again.",
          )
      SessionState.Aborted ->
          pipelineStore.markFailed(
              pipeline.id,
              failureSummary =
                  "The Flow session for this issue was aborted.\n\n" +
                      "Clear this pipeline to let Flow try the issue again.",
          )
      SessionState.Pending,
      SessionState.Running -> null // still working
    }
  }

  private fun parsePrNumber(
      prUrl: String,
  ): Int = Regex("""/pull/(\d+)""").find(prUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 0

  private suspend fun observePrOpen(
      pipeline: IssuePipeline,
  ): PipelineTransition? {
    val prNumber = pipeline.prNumber ?: return null // PR_OPEN always has one; defensive.

    return when (val state = prClient.getPullRequestState(pipeline.repoFullName, prNumber)) {
      PullRequestState.Open -> null
      is PullRequestState.Merged ->
          pipelineStore.markAwaitingMergeChecks(pipeline.id, state.mergeCommitSha)
      PullRequestState.ClosedUnmerged ->
          pipelineStore.markFailed(
              pipeline.id,
              failureSummary =
                  "The pull request ${pipeline.prUrl} was closed without merging, so this issue " +
                      "wasn't completed. Clear this pipeline to let Flow try the issue again.",
          )
    }
  }

  private suspend fun observeAwaitingMergeChecks(
      pipeline: IssuePipeline,
  ): PipelineTransition? {
    val sha = pipeline.mergeCommitSha ?: return null // set on the transition into this state.

    return when (val status = prClient.getMergeCheckStatus(pipeline.repoFullName, sha)) {
      MergeCheckStatus.Green -> markDone(pipeline, mergeNote = "the merge checks passed")
      is MergeCheckStatus.Red ->
          pipelineStore.markFailed(
              pipeline.id,
              failureSummary =
                  "The merge checks on ${pipeline.prUrl} failed: " +
                      "${status.failingRunNames.joinToString(", ")}. This issue was **not** " +
                      "completed. Clear this pipeline to try again once the checks are fixed.",
          )
      MergeCheckStatus.Pending -> null
      MergeCheckStatus.NoRuns ->
          if (isPastGracePeriod(pipeline)) {
            markDone(
                pipeline,
                mergeNote = "no merge workflows are configured, so merge alone suffices",
            )
          } else {
            log.info(
                "pipeline {} awaiting merge checks on {}: no runs yet, within grace period",
                pipeline.id.id,
                sha,
            )
            null
          }
    }
  }

  private suspend fun markDone(
      pipeline: IssuePipeline,
      mergeNote: String,
  ): PipelineTransition =
      pipelineStore.markDone(
          pipeline.id,
          annotationMarkdown =
              "✅ Completed by Flow — $mergeNote.\n\n" +
                  "- Pull request: ${pipeline.prUrl}\n" +
                  "- Session: ${pipeline.sessionId?.id ?: "n/a"}",
      )

  private fun isPastGracePeriod(
      pipeline: IssuePipeline,
  ): Boolean {
    // updatedAt was set when the pipeline entered AWAITING_MERGE_CHECKS and isn't touched while
    // pending, so it measures how long we've been waiting for runs to appear.
    val elapsed = Duration.between(pipeline.updatedAt, clock.instant())
    return elapsed >= noRunsGracePeriod
  }
}
