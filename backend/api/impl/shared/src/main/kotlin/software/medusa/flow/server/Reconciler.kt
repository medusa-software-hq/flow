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
    // Discovers repos with an open `flow:ready` issue (one installation-wide GitHub search), so the
    // scheduler (no-arg) reconcile can pick a fresh repo whose only signal so far is a ready issue
    // —
    // without this a brand-new repo's *first* pick could only come from a webhook. Defaults to
    // none.
    private val discoverReadyRepos: suspend () -> Set<String> = { emptySet() },
    // When set, the reconciler *only ever* acts on repos owned by [orgOwner] — every path
    // (discovery,
    // live pipelines, pending outbox, and even a webhook's repo-scoped trigger) is fenced to it.
    // The
    // App can't write outside our org (label/close calls 403), so acting on a foreign repo only
    // creates inert junk + stuck outbox in our own DB. Null (tests/local) disables the fence.
    private val orgOwner: String? = null,
) {
  private val log = LoggerFactory.getLogger(Reconciler::class.java)

  /**
   * Reconciles [repoFullName], or — when null — every currently-relevant repo. Returns a per-repo
   * summary. Repos are processed independently; each is serialized against concurrent triggers by
   * [repoLock]. Any repo outside [orgOwner] is dropped before processing.
   */
  suspend fun reconcile(
      repoFullName: String?,
  ): List<ReconcileSummary> {
    val candidateRepos = repoFullName?.let { listOf(it) } ?: relevantRepos()
    val targetRepos = candidateRepos.filter(::isInOrg)

    val dropped = candidateRepos - targetRepos.toSet()
    if (dropped.isNotEmpty()) {
      log.warn("skipping repos outside org '{}': {}", orgOwner, dropped)
    }

    log.info(
        "reconcile start: trigger={}, repos={}",
        repoFullName ?: "<all>",
        targetRepos,
    )

    return targetRepos.map { repo -> repoLock.withRepoLock(repo) { reconcileRepo(repo) } }
  }

  private fun isInOrg(
      repoFullName: String,
  ): Boolean = orgOwner == null || repoFullName.substringBefore('/') == orgOwner

  /**
   * "Relevant" for a full run = repos with a live pipeline ∪ repos with pending outbox ∪ repos with
   * an open `flow:ready` issue ([discoverReadyRepos]). The first two keep in-flight work
   * converging; the third lets the scheduler *discover* a repo whose only signal so far is a ready
   * issue.
   *
   * Discovery is best-effort: a failing search (rate limit, transient error) must not stop the
   * backstop from converging repos that are already relevant, so it's caught and logged.
   */
  private suspend fun relevantRepos(): List<String> {
    val fromLivePipelines = pipelineStore.listLive().map { it.repoFullName }
    val fromPendingOutbox = outboxStore.reposWithPendingEntries()
    val fromDiscovery =
        try {
          discoverReadyRepos()
        } catch (e: Exception) {
          log.warn("ready-repo discovery failed; reconciling only already-relevant repos", e)
          emptySet()
        }
    return (fromLivePipelines + fromPendingOutbox + fromDiscovery).distinct()
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
