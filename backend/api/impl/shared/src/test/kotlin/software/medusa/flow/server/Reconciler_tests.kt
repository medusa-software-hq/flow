package software.medusa.flow.server

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class Reconciler_tests {
  private val repo = "acme/app"

  private fun fixture():
      Triple<InMemoryIssuePipelineStore, InMemoryGithubOutboxStore, FakeGitHubIssueClient> {
    val backend = InMemoryPipelineBackend()
    return Triple(
        InMemoryIssuePipelineStore(backend),
        InMemoryGithubOutboxStore(backend),
        FakeGitHubIssueClient(),
    )
  }

  private fun reconciler(
      pipelines: InMemoryIssuePipelineStore,
      outbox: InMemoryGithubOutboxStore,
      github: FakeGitHubIssueClient,
      observer: PipelineObserver = PipelineObserver.Noop,
      picker: PipelinePicker = PipelinePicker.Noop,
      repoLock: RepoLock = InMemoryRepoLock(),
      discoverReadyRepos: suspend () -> Set<String> = { emptySet() },
  ): Reconciler =
      Reconciler(
          pipelineStore = pipelines,
          outboxStore = outbox,
          dispatcher = OutboxDispatcher(outbox, github),
          observer = observer,
          picker = picker,
          repoLock = repoLock,
          discoverReadyRepos = discoverReadyRepos,
      )

  @Test
  fun `reconcile drains a repo's seeded outbox entries`() = runBlocking {
    val (pipelines, outbox, github) = fixture()
    pipelines.pick(repo, 1, "Issue 1", "u", SessionId("s1")) // enqueues add-label(flow:in-progress)

    val summaries = reconciler(pipelines, outbox, github).reconcile(repo)

    assertEquals(1, summaries.single().drainedCount)
    assertTrue(
        github.calls.contains(FakeGitHubIssueClient.Call.AddLabel(repo, 1, "flow:in-progress")),
    )
    // Fully drained → a second reconcile is a no-op.
    assertEquals(0, reconciler(pipelines, outbox, github).reconcile(repo).single().drainedCount)
  }

  @Test
  fun `a full run reconciles every relevant repo (live pipelines and pending outbox)`() =
      runBlocking {
        val (pipelines, outbox, github) = fixture()
        pipelines.pick("acme/a", 1, "t", "u", SessionId("sa"))
        pipelines.pick("acme/b", 2, "t", "u", SessionId("sb"))

        val summaries = reconciler(pipelines, outbox, github).reconcile(repoFullName = null)

        assertEquals(setOf("acme/a", "acme/b"), summaries.map { it.repoFullName }.toSet())
      }

  @Test
  fun `a full run also scans configured discovery repos with no pipeline or outbox yet`() =
      runBlocking {
        val (pipelines, outbox, github) = fixture()
        // No pick, no pending outbox — the repo's only signal is that it's in the discovery set. A
        // pre-fix scheduler run would have scanned nothing.
        val summaries =
            reconciler(
                    pipelines,
                    outbox,
                    github,
                    picker = PipelinePicker { 1 },
                    discoverReadyRepos = { setOf("acme/fresh") },
                )
                .reconcile(repoFullName = null)

        assertEquals(setOf("acme/fresh"), summaries.map { it.repoFullName }.toSet())
        assertEquals(1, summaries.single().pickedCount)
      }

  @Test
  fun `a failing discovery search still reconciles already-relevant repos`() = runBlocking {
    val (pipelines, outbox, github) = fixture()
    pipelines.pick("acme/live", 1, "t", "u", SessionId("s1")) // a live pipeline → relevant

    val summaries =
        reconciler(
                pipelines,
                outbox,
                github,
                discoverReadyRepos = { error("GitHub search is down") },
            )
            .reconcile(repoFullName = null)

    // Discovery blew up, but the live repo is still reconciled (backstop stays robust).
    assertEquals(setOf("acme/live"), summaries.map { it.repoFullName }.toSet())
  }

  @Test
  fun `the phase counts are reported per repo`() = runBlocking {
    val (pipelines, outbox, github) = fixture()
    pipelines.pick(repo, 1, "t", "u", SessionId("s1"))

    val summary =
        reconciler(
                pipelines,
                outbox,
                github,
                observer = PipelineObserver { 3 },
                picker = PipelinePicker { 1 },
            )
            .reconcile(repo)
            .single()

    assertEquals(1, summary.pickedCount)
    assertEquals(3, summary.advancedCount)
    assertEquals(1, summary.drainedCount)
  }

  @Test
  fun `concurrent reconciles of one repo serialize via the repo lock`() = runBlocking {
    val (pipelines, outbox, github) = fixture()

    // A picker that records max concurrency and blocks until released, proving the two calls don't
    // run the repo's phases at the same time.
    val active = AtomicInteger(0)
    val maxObserved = AtomicInteger(0)
    val release = CompletableDeferred<Unit>()
    val bothEntered = CompletableDeferred<Unit>()
    val entered = AtomicInteger(0)

    val picker = PipelinePicker {
      val now = active.incrementAndGet()
      maxObserved.updateAndGet { max -> maxOf(max, now) }
      if (entered.incrementAndGet() == 1) {
        // First entrant waits so the second has a chance to (try to) enter concurrently.
        release.await()
      } else {
        bothEntered.complete(Unit)
      }
      active.decrementAndGet()
      0
    }

    val sharedLock = InMemoryRepoLock()
    val r1 = reconciler(pipelines, outbox, github, picker = picker, repoLock = sharedLock)

    coroutineScope {
      val first = async { r1.reconcile(repo) }
      val second = async { r1.reconcile(repo) }

      // Give the second a moment to block on the lock, then let the first finish.
      delay(100)
      release.complete(Unit)
      bothEntered.await()

      first.await()
      second.await()
    }

    assertEquals(1, maxObserved.get()) // never two picks in flight at once
  }
}
