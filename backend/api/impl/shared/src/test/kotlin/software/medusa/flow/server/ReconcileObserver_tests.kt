package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class SteppableClock(
    var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = SteppableClock(current, zone)

  override fun instant(): Instant = current

  fun advance(duration: Duration) {
    current = current.plus(duration)
  }
}

class ReconcileObserver_tests {
  private val repo = "acme/app"

  private class Fixture(clock: SteppableClock) {
    val backend = InMemoryPipelineBackend(clock = clock)
    val pipelines = InMemoryIssuePipelineStore(backend)
    val outbox = InMemoryGithubOutboxStore(backend)
    val sessions = InMemorySessionStore(clock = clock)
    val prClient = FakeGitHubPrClient()
    val observer =
        ReconcileObserver(
            pipelines,
            sessions,
            prClient,
            clock,
            noRunsGracePeriod = Duration.ofMinutes(6),
        )
  }

  /** Picks an issue and drives it to `PR_OPEN` (pr #7). */
  private suspend fun Fixture.pipelineInPrOpen(): IssuePipeline {
    val picked =
        (pipelines.pick(repo, 1, "Issue 1", "https://x/1", SessionId("s1")) as PickResult.Picked)
            .pipeline
    pipelines.markPrOpen(picked.id, prNumber = 7, prUrl = "https://x/pr/7")
    return pipelines.get(picked.id)!!
  }

  private suspend fun Fixture.pipelineAwaiting(sha: String): IssuePipeline {
    val prOpen = pipelineInPrOpen()
    prClient.prStateByNumber[7] = PullRequestState.Merged(sha)
    observer.observe(repo)
    return pipelines.get(prOpen.id)!!
  }

  private fun outboxActions(fx: Fixture, issue: Int) =
      fx.backend.outboxEntries
          .filter { it.issueNumber == issue }
          .sortedBy { it.seq }
          .map { it.action }

  @Test
  fun `PR_OPEN with an open PR is a no-op`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineInPrOpen()

    assertEquals(0, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.PrOpen, fx.pipelines.get(p.id)!!.state)
  }

  @Test
  fun `PR_OPEN merged advances to AWAITING_MERGE_CHECKS and records the sha`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineInPrOpen()
    fx.prClient.prStateByNumber[7] = PullRequestState.Merged("deadbeef")

    assertEquals(1, fx.observer.observe(repo))
    val updated = fx.pipelines.get(p.id)!!
    assertEquals(IssuePipelineState.AwaitingMergeChecks, updated.state)
    assertEquals("deadbeef", updated.mergeCommitSha)
  }

  @Test
  fun `PR_OPEN closed-unmerged fails with a label swap and annotation`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineInPrOpen()
    fx.prClient.prStateByNumber[7] = PullRequestState.ClosedUnmerged

    assertEquals(1, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.Failed, fx.pipelines.get(p.id)!!.state)
    assertTrue(
        outboxActions(fx, 1).containsAll(listOf(OutboxAction.AddLabel, OutboxAction.PostComment))
    )
  }

  @Test
  fun `AWAITING green closes the issue - remove label, comment, close`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineAwaiting("sha1")
    fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.Green

    assertEquals(1, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.Done, fx.pipelines.get(p.id)!!.state)
    // Done choreography: remove flow:pr-open, post comment, close issue.
    assertTrue(
        outboxActions(fx, 1).takeLast(3) ==
            listOf(OutboxAction.RemoveLabel, OutboxAction.PostComment, OutboxAction.CloseIssue)
    )
  }

  @Test
  fun `AWAITING red fails and names the failing runs`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineAwaiting("sha1")
    fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.Red(listOf("Deploy API"))

    assertEquals(1, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.Failed, fx.pipelines.get(p.id)!!.state)
    assertTrue(fx.pipelines.get(p.id)!!.failureSummary!!.contains("Deploy API"))
  }

  @Test
  fun `AWAITING pending is a no-op`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val p = fx.pipelineAwaiting("sha1")
    fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.Pending

    assertEquals(0, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.AwaitingMergeChecks, fx.pipelines.get(p.id)!!.state)
  }

  @Test
  fun `AWAITING with no runs waits within the grace period, then vacuously succeeds`() =
      runBlocking {
        val clock = SteppableClock(Instant.parse("2026-04-01T00:00:00Z"))
        val fx = Fixture(clock)
        val p = fx.pipelineAwaiting("sha1")
        fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.NoRuns

        // Within grace → still awaiting.
        clock.advance(Duration.ofMinutes(5))
        assertEquals(0, fx.observer.observe(repo))
        assertEquals(IssuePipelineState.AwaitingMergeChecks, fx.pipelines.get(p.id)!!.state)

        // Past grace → vacuous success (no merge workflows configured).
        clock.advance(Duration.ofMinutes(2))
        assertEquals(1, fx.observer.observe(repo))
        assertEquals(IssuePipelineState.Done, fx.pipelines.get(p.id)!!.state)
      }

  @Test
  fun `checks appearing late flip a no-runs pipeline to green before the grace elapses`() =
      runBlocking {
        val clock = SteppableClock(Instant.parse("2026-04-01T00:00:00Z"))
        val fx = Fixture(clock)
        val p = fx.pipelineAwaiting("sha1")

        // First reconcile: no runs yet, within grace.
        fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.NoRuns
        clock.advance(Duration.ofMinutes(1))
        fx.observer.observe(repo)
        assertEquals(IssuePipelineState.AwaitingMergeChecks, fx.pipelines.get(p.id)!!.state)

        // Runs appear and pass before the grace would have elapsed → DONE, not vacuous.
        fx.prClient.mergeStatusBySha["sha1"] = MergeCheckStatus.Green
        clock.advance(Duration.ofMinutes(1))
        assertEquals(1, fx.observer.observe(repo))
        assertEquals(IssuePipelineState.Done, fx.pipelines.get(p.id)!!.state)
      }

  @Test
  fun `an IN_PROGRESS pipeline whose session is still running is left alone`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val session = fx.sessions.create(repo, "task", "flow-reconciler", engine = Engine.Unspecified)
    fx.pipelines.pick(repo, 1, "Issue 1", "u", session.id)
    fx.sessions.claimNext(supportedEngines = emptySet()) // session → RUNNING

    assertEquals(0, fx.observer.observe(repo))
    assertEquals(IssuePipelineState.InProgress, fx.pipelines.list(repo).single().state)
  }

  @Test
  fun `worker-lost backstop - IN_PROGRESS with a FAILED session fails the pipeline and holds the mutex`() =
      runBlocking {
        val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
        val session =
            fx.sessions.create(repo, "task", "flow-reconciler", engine = Engine.Unspecified)
        val p =
            (fx.pipelines.pick(repo, 1, "Issue 1", "u", session.id) as PickResult.Picked).pipeline
        fx.sessions.claimNext(supportedEngines = emptySet())
        fx.sessions.fail(session.id, "Worker lost")

        assertEquals(1, fx.observer.observe(repo))
        assertEquals(IssuePipelineState.Failed, fx.pipelines.get(p.id)!!.state)
        assertTrue(fx.pipelines.isRepoBusy(repo)) // FAILED still holds the mutex
      }

  @Test
  fun `backstop - IN_PROGRESS with a COMPLETED session advances to PR_OPEN`() = runBlocking {
    val fx = Fixture(SteppableClock(Instant.parse("2026-04-01T00:00:00Z")))
    val session = fx.sessions.create(repo, "task", "flow-reconciler", engine = Engine.Unspecified)
    val p = (fx.pipelines.pick(repo, 1, "Issue 1", "u", session.id) as PickResult.Picked).pipeline
    fx.sessions.claimNext(supportedEngines = emptySet())
    fx.sessions.complete(session.id, "https://github.com/acme/app/pull/9")

    assertEquals(1, fx.observer.observe(repo))
    val updated = fx.pipelines.get(p.id)!!
    assertEquals(IssuePipelineState.PrOpen, updated.state)
    assertEquals(9, updated.prNumber)
  }
}
