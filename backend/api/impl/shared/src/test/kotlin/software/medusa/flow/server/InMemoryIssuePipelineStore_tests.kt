package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class MovableClock(
    var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = MovableClock(current, zone)

  override fun instant(): Instant = current

  fun advance(duration: Duration) {
    current = current.plus(duration)
  }
}

class InMemoryIssuePipelineStore_tests {
  private fun newStores(
      clock: MovableClock = MovableClock(Instant.parse("2026-02-01T00:00:00Z")),
  ): Triple<InMemoryIssuePipelineStore, InMemoryGithubOutboxStore, MovableClock> {
    val backend = InMemoryPipelineBackend(clock = clock)
    return Triple(InMemoryIssuePipelineStore(backend), InMemoryGithubOutboxStore(backend), clock)
  }

  private suspend fun InMemoryIssuePipelineStore.pickA(
      repo: String = "acme/app",
      issue: Int = 1,
  ): IssuePipeline {
    val result = pick(repo, issue, "Issue $issue", "https://x/$issue", SessionId("s$issue"))
    return assertIs<PickResult.Picked>(result).pipeline
  }

  private fun actions(entries: List<OutboxEntry>): List<Pair<OutboxAction, String>> =
      entries.sortedBy { it.seq }.map { it.action to it.payload }

  @Test
  fun `pick starts a pipeline IN_PROGRESS and enqueues the in-progress label`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    val pipeline = pipelines.pickA()

    assertEquals(IssuePipelineState.InProgress, pipeline.state)
    assertEquals(SessionId("s1"), pipeline.sessionId)
    assertTrue(pipelines.isRepoBusy("acme/app"))

    assertEquals(
        listOf(OutboxAction.AddLabel to OutboxPayloads.label("flow:in-progress")),
        actions(outbox.dueEntries("acme/app")),
    )
  }

  @Test
  fun `pick links a shadow session - findBySessionId matches primary or shadow`() = runBlocking {
    val (pipelines, _, _) = newStores()

    val result =
        pipelines.pick(
            repoFullName = "acme/app",
            issueNumber = 7,
            issueTitle = "Issue 7",
            issueUrl = "https://x/7",
            sessionId = SessionId("primary"),
            shadowSessionId = SessionId("shadow"),
        )
    val pipeline = assertIs<PickResult.Picked>(result).pipeline

    assertEquals(SessionId("primary"), pipeline.sessionId)
    assertEquals(SessionId("shadow"), pipeline.shadowSessionId)
    // Either session resolves back to the same pipeline — display enrichment works for both tabs.
    assertEquals(pipeline.id, pipelines.findBySessionId(SessionId("primary"))?.id)
    assertEquals(pipeline.id, pipelines.findBySessionId(SessionId("shadow"))?.id)
    assertEquals(null, pipelines.findBySessionId(SessionId("unrelated")))
  }

  @Test
  fun `a busy repo rejects a second pick (mutex)`() = runBlocking {
    val (pipelines, _, _) = newStores()

    pipelines.pickA(issue = 1)
    val second = pipelines.pick("acme/app", 2, "Issue 2", "https://x/2", SessionId("s2"))

    assertIs<PickResult.RepoBusy>(second)
    // The rejected pick left no row.
    assertEquals(1, pipelines.list("acme/app").size)
  }

  @Test
  fun `a different repo can be picked concurrently`() = runBlocking {
    val (pipelines, _, _) = newStores()

    pipelines.pickA(repo = "acme/app")
    val other = pipelines.pick("acme/other", 1, "t", "u", SessionId("s"))

    assertIs<PickResult.Picked>(other)
  }

  @Test
  fun `FAILED holds the mutex - clearing releases it and re-pick creates a new row`() =
      runBlocking {
        val (pipelines, _, _) = newStores()

        val first = pipelines.pickA()
        assertIs<PipelineTransition.Applied>(pipelines.markFailed(first.id, "boom"))

        // Still busy — FAILED holds the mutex.
        assertTrue(pipelines.isRepoBusy("acme/app"))
        assertIs<PickResult.RepoBusy>(
            pipelines.pick("acme/app", 1, "t", "u", SessionId("s1b")),
        )

        // Clear → released.
        assertIs<PipelineTransition.Applied>(pipelines.clear(first.id))
        assertFalse(pipelines.isRepoBusy("acme/app"))

        val repick = pipelines.pick("acme/app", 1, "t", "u", SessionId("s1c"))
        val repicked = assertIs<PickResult.Picked>(repick).pipeline
        assertTrue(repicked.id != first.id) // a brand new row
        assertEquals(2, pipelines.list("acme/app").size) // history preserved
      }

  @Test
  fun `the happy-path transitions each enqueue the expected outbox entries`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    val p = pipelines.pickA()
    assertIs<PipelineTransition.Applied>(pipelines.markPrOpen(p.id, prNumber = 7, prUrl = "pr7"))
    assertIs<PipelineTransition.Applied>(pipelines.markAwaitingMergeChecks(p.id, "sha"))
    assertIs<PipelineTransition.Applied>(pipelines.markDone(p.id, "All green — closing"))

    assertEquals(IssuePipelineState.Done, pipelines.get(p.id)!!.state)
    assertFalse(pipelines.isRepoBusy("acme/app")) // DONE is terminal, mutex released

    assertEquals(
        listOf(
            OutboxAction.AddLabel to OutboxPayloads.label("flow:in-progress"),
            // markPrOpen
            OutboxAction.RemoveLabel to OutboxPayloads.label("flow:in-progress"),
            OutboxAction.AddLabel to OutboxPayloads.label("flow:pr-open"),
            // markAwaitingMergeChecks: no outbox
            // markDone
            OutboxAction.RemoveLabel to OutboxPayloads.label("flow:pr-open"),
            OutboxAction.PostComment to OutboxPayloads.comment("All green — closing"),
            OutboxAction.CloseIssue to OutboxPayloads.closeIssue,
        ),
        actions(allEntriesFor(outbox, "acme/app", 1)),
    )
  }

  @Test
  fun `failing from PR_OPEN removes the pr-open label, adds failed, and comments`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    val p = pipelines.pickA()
    pipelines.markPrOpen(p.id, 7, "pr7")
    assertIs<PipelineTransition.Applied>(pipelines.markFailed(p.id, "PR checks red"))

    // The last three entries are the failure choreography from PR_OPEN.
    val entries = actions(allEntriesFor(outbox, "acme/app", 1)).takeLast(3)
    assertEquals(
        listOf(
            OutboxAction.RemoveLabel to OutboxPayloads.label("flow:pr-open"),
            OutboxAction.AddLabel to OutboxPayloads.label("flow:failed"),
            OutboxAction.PostComment to OutboxPayloads.comment("PR checks red"),
        ),
        entries,
    )
  }

  @Test
  fun `illegal transitions are rejected and change nothing`() = runBlocking {
    val (pipelines, _, _) = newStores()

    val p = pipelines.pickA()

    // Can't go straight to DONE from IN_PROGRESS.
    assertIs<PipelineTransition.Rejected>(pipelines.markDone(p.id, "x"))
    // Can't clear a non-FAILED pipeline.
    assertIs<PipelineTransition.Rejected>(pipelines.clear(p.id))
    // A transition against an unknown id is rejected.
    assertIs<PipelineTransition.Rejected>(pipelines.markPrOpen(IssuePipelineId("nope"), 1, "u"))

    assertEquals(IssuePipelineState.InProgress, pipelines.get(p.id)!!.state)
  }

  @Test
  fun `listLive excludes DONE and cleared, list returns newest-first`() = runBlocking {
    val (pipelines, _, clock) = newStores()

    val a = pipelines.pickA(repo = "acme/a", issue = 1)
    clock.advance(Duration.ofSeconds(1))
    val b = pipelines.pickA(repo = "acme/b", issue = 2)

    // Drive a to DONE.
    pipelines.markPrOpen(a.id, 1, "pr")
    pipelines.markAwaitingMergeChecks(a.id, "sha")
    pipelines.markDone(a.id, "done")

    assertEquals(listOf(b.id), pipelines.listLive().map { it.id })
    assertEquals(listOf(b.id, a.id), pipelines.list(null).map { it.id }) // newest-first
  }

  private suspend fun allEntriesFor(
      outbox: InMemoryGithubOutboxStore,
      repo: String,
      issue: Int,
  ): List<OutboxEntry> {
    // Drain the FIFO head repeatedly, marking dispatched, to collect every entry in order.
    val collected = mutableListOf<OutboxEntry>()
    while (true) {
      val head = outbox.dueEntries(repo).firstOrNull { it.issueNumber == issue } ?: break
      collected += head
      outbox.markDispatched(head.id)
    }
    return collected
  }

  @Test
  fun `outbox is strict FIFO within an issue - the head blocks until dispatched`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    val p = pipelines.pickA(issue = 1) // seq 1: add-label(in-progress)
    pipelines.markPrOpen(p.id, 1, "pr") // seq 2: remove(in-progress), seq 3: add(pr-open)

    // Only the head (lowest undispatched seq) is due — later entries wait behind it.
    val head = outbox.dueEntries("acme/app").single()
    assertEquals(1, head.seq)

    // A failed dispatch keeps the head at the front (still the only due entry).
    outbox.markFailed(head.id, "boom", Duration.ofMinutes(1))
    assertTrue(outbox.dueEntries("acme/app").isEmpty()) // backed off, not yet due

    // Dispatching the head advances the queue to seq 2.
    outbox.markDispatched(head.id)
    assertEquals(2, outbox.dueEntries("acme/app").single().seq)
  }

  @Test
  fun `a stuck entry in one issue does not block a different issue`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    // Issue 1: pick then drive to DONE so the repo frees up and its outbox has entries.
    val p1 = pipelines.pickA(issue = 1)
    pipelines.markPrOpen(p1.id, 1, "pr")
    pipelines.markAwaitingMergeChecks(p1.id, "sha")
    pipelines.markDone(p1.id, "done")

    // Issue 2 in the same repo (now free): its own outbox queue.
    pipelines.pickA(issue = 2)

    // Block issue 1's head far into the future.
    val issue1Head = outbox.dueEntries("acme/app").single { it.issueNumber == 1 }
    outbox.markFailed(issue1Head.id, "boom", Duration.ofHours(1))

    // Issue 2's head is still due — independent queue.
    val due = outbox.dueEntries("acme/app")
    assertTrue(due.none { it.issueNumber == 1 }) // issue 1 backed off
    assertEquals(2, due.single().issueNumber)
  }

  @Test
  fun `stuckEntries surfaces entries past the attempt threshold`() = runBlocking {
    val (pipelines, outbox, _) = newStores()

    val p = pipelines.pickA()
    val head = outbox.dueEntries("acme/app").single()

    repeat(3) { outbox.markFailed(head.id, "boom", Duration.ZERO) }

    assertTrue(outbox.stuckEntries(maxAttempts = 5).isEmpty())
    assertEquals(listOf(head.id), outbox.stuckEntries(maxAttempts = 3).map { it.id })
    assertEquals(IssuePipelineState.InProgress, pipelines.get(p.id)!!.state)
  }
}
