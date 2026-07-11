package software.medusa.flow.server

import org.slf4j.LoggerFactory

/** Per-repo outcome of a reconcile pass, surfaced in the RPC response and the logs. */
data class ReconcileSummary(
    val repoFullName: String,
    val pickedCount: Int,
    val advancedCount: Int,
    val drainedCount: Int,
)

/**
 * The reconcile orchestrator behind `ReconcileService.Reconcile`. For each target repo, under a
 * per-repo lock, it runs the fixed phase structure:
 * ```
 * drain → observe → pick → drain
 * ```
 *
 * The leading drain clears any backlog before observing; the trailing drain flushes the outbox
 * entries that observe/pick just enqueued, so a single call makes as much progress as it can.
 *
 * Observe and pick are injected ([PipelineObserver] / [PipelinePicker]); this skeleton wires their
 * `Noop` stubs, filled in by stories 06/07. Draining ([OutboxDispatcher]) is already real.
 */
class Reconciler(
    private val pipelineStore: IssuePipelineStore,
    private val outboxStore: GithubOutboxStore,
    private val dispatcher: OutboxDispatcher,
    private val observer: PipelineObserver,
    private val picker: PipelinePicker,
    private val repoLock: RepoLock,
) {
  private val log = LoggerFactory.getLogger(Reconciler::class.java)

  /**
   * Reconciles [repoFullName], or — when null — every currently-relevant repo. Returns a per-repo
   * summary. Repos are processed independently; each is serialized against concurrent triggers by
   * [repoLock].
   */
  suspend fun reconcile(
      repoFullName: String?,
  ): List<ReconcileSummary> {
    val targetRepos = repoFullName?.let { listOf(it) } ?: relevantRepos()

    log.info(
        "reconcile start: trigger={}, repos={}",
        repoFullName ?: "<all>",
        targetRepos,
    )

    return targetRepos.map { repo -> repoLock.withRepoLock(repo) { reconcileRepo(repo) } }
  }

  /**
   * "Relevant" for a full run = repos with a live pipeline ∪ repos with pending outbox. (Repos that
   * merely have `ready` issues but no pipeline yet become relevant once the pick phase learns to
   * discover them — story 07.)
   */
  private suspend fun relevantRepos(): List<String> {
    val fromLivePipelines = pipelineStore.listLive().map { it.repoFullName }
    val fromPendingOutbox = outboxStore.reposWithPendingEntries()
    return (fromLivePipelines + fromPendingOutbox).distinct()
  }

  private suspend fun reconcileRepo(
      repoFullName: String,
  ): ReconcileSummary {
    val startNanos = System.nanoTime()

    val drainedBefore = dispatcher.drain(repoFullName)
    val advanced = observer.observe(repoFullName)
    val picked = picker.pick(repoFullName)
    val drainedAfter = dispatcher.drain(repoFullName)

    val summary =
        ReconcileSummary(
            repoFullName = repoFullName,
            pickedCount = picked,
            advancedCount = advanced,
            drainedCount = drainedBefore + drainedAfter,
        )

    log.info(
        "reconcile repo={} picked={} advanced={} drained={} durationMs={}",
        repoFullName,
        summary.pickedCount,
        summary.advancedCount,
        summary.drainedCount,
        (System.nanoTime() - startNanos) / 1_000_000,
    )

    return summary
  }
}
