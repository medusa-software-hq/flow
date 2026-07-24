package software.medusa.flow.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

private class TickingClock(
    var current: Instant,
) : Clock {
  override fun now(): Instant = current

  fun advance(duration: Duration) {
    current += duration
  }
}

class OutboxDispatcher_tests {
  private val repo = "acme/app"

  private fun fixture(
      clock: TickingClock = TickingClock(Instant.parse("2026-03-01T00:00:00Z")),
  ): Triple<InMemoryIssuePipelineStore, InMemoryGithubOutboxStore, TickingClock> {
    val backend = InMemoryPipelineBackend(clock = clock)
    return Triple(InMemoryIssuePipelineStore(backend), InMemoryGithubOutboxStore(backend), clock)
  }

  private suspend fun InMemoryIssuePipelineStore.pick(issue: Int) =
      pick(repo, issue, "Issue $issue", "https://x/$issue", SessionId("s$issue"))

  @Test
  fun `drain executes a full pipeline's entries in order and marks them dispatched`() =
      runBlocking {
        val (pipelines, outbox, _) = fixture()
        val github = FakeGitHubIssueClient()
        val dispatcher = OutboxDispatcher(outbox, github)

        val p = (pipelines.pick(1) as PickResult.Picked).pipeline
        pipelines.markPrOpen(p.id, 7, "pr")
        pipelines.markAwaitingMergeChecks(p.id, "sha")
        pipelines.markDone(p.id, "done")

        // One issue's queue drains one head per call, in seq order.
        while (dispatcher.drain(repo) > 0) {
          /* keep draining until the queue is empty */
        }

        assertEquals(
            listOf(
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.AddLabel(repo, 1, "flow:in-progress"),
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.RemoveLabel(repo, 1, "flow:in-progress"),
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.AddLabel(repo, 1, "flow:pr-open"),
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.RemoveLabel(repo, 1, "flow:pr-open"),
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.PostComment(repo, 1, "done"),
                FakeGitHubIssueClient.Call.EnsureLabels(repo),
                FakeGitHubIssueClient.Call.CloseIssue(repo, 1),
            ),
            github.calls,
        )

        // Everything delivered → nothing left due, and re-running is a no-op.
        assertEquals(0, dispatcher.drain(repo))
      }

  @Test
  fun `a re-run after full delivery is a no-op (idempotent replay)`() = runBlocking {
    val (pipelines, outbox, _) = fixture()
    val github = FakeGitHubIssueClient()
    val dispatcher = OutboxDispatcher(outbox, github)

    pipelines.pick(1)
    assertEquals(1, dispatcher.drain(repo)) // add-label
    val callsAfterFirst = github.calls.size

    assertEquals(0, dispatcher.drain(repo))
    assertEquals(callsAfterFirst, github.calls.size) // no further GitHub calls
  }

  @Test
  fun `a failing entry backs off and blocks only its own issue`() = runBlocking {
    val (pipelines, outbox, clock) = fixture()
    val github = FakeGitHubIssueClient()
    val dispatcher = OutboxDispatcher(outbox, github, baseBackoff = 30.seconds)

    // Issue 1: pick then drive to DONE so the repo frees; issue 2 gets its own queue.
    val p1 = (pipelines.pick(1) as PickResult.Picked).pipeline
    pipelines.markPrOpen(p1.id, 1, "pr")
    pipelines.markAwaitingMergeChecks(p1.id, "sha")
    pipelines.markDone(p1.id, "done")
    pipelines.pick(2)

    // Fail issue 1's head (its add-label); let everything else succeed.
    github.failureFor = { call ->
      if (call is FakeGitHubIssueClient.Call.AddLabel && call.issueNumber == 1) "boom" else null
    }

    dispatcher.drain(repo)

    // Issue 1's head is not dispatched (still queued, backed off); issue 2's add-label went
    // through.
    assertTrue(
        github.calls.any { it == FakeGitHubIssueClient.Call.AddLabel(repo, 2, "flow:in-progress") }
    )
    assertTrue(
        github.calls.none { it == FakeGitHubIssueClient.Call.AddLabel(repo, 1, "flow:in-progress") }
    )

    // Issue 1's head is backed off — not due yet.
    assertTrue(outbox.dueEntries(repo).none { it.issueNumber == 1 })

    // Once it recovers and the backoff elapses, the whole issue-1 queue drains.
    github.failureFor = { null }
    clock.advance(31.seconds)
    while (dispatcher.drain(repo) > 0) {
      /* drain */
    }
    assertTrue(github.calls.contains(FakeGitHubIssueClient.Call.CloseIssue(repo, 1)))
  }

  @Test
  fun `backoff grows with attempts and a persistently failing entry becomes stuck but is never dropped`() =
      runBlocking {
        val (pipelines, outbox, clock) = fixture()
        val github = FakeGitHubIssueClient()
        val dispatcher = OutboxDispatcher(outbox, github, baseBackoff = 30.seconds)
        // Fail the entry action itself (label-ensure is best-effort and separate).
        github.failureFor = { call ->
          if (call is FakeGitHubIssueClient.Call.AddLabel) "always fails" else null
        }

        pipelines.pick(1) // one entry: add-label

        // Attempt 1: backoff 30s. Not stuck yet.
        assertEquals(0, dispatcher.drain(repo))
        assertTrue(outbox.dueEntries(repo).isEmpty()) // backed off
        clock.advance(29.seconds)
        assertTrue(outbox.dueEntries(repo).isEmpty()) // still backed off
        clock.advance(2.seconds) // now past 30s

        // Attempt 2: backoff 60s.
        dispatcher.drain(repo)
        clock.advance(59.seconds)
        assertTrue(outbox.dueEntries(repo).isEmpty())
        clock.advance(2.seconds)

        // A few more attempts push it past the stuck threshold — still present, never dropped.
        repeat(4) {
          dispatcher.drain(repo)
          clock.advance(40.minutes) // well past any capped backoff
        }
        val stuck = outbox.stuckEntries(GithubOutboxStore.defaultStuckAttempts)
        assertEquals(1, stuck.size)
        assertEquals(1, stuck.single().issueNumber)
      }
}
