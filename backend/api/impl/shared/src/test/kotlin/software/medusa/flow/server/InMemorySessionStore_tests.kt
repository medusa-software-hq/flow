package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A [Clock] whose instant can be advanced, so expiry and ordering are deterministic. */
private class MutableClock(
    var current: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
  override fun getZone(): ZoneId = zone

  override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

  override fun instant(): Instant = current

  fun advance(duration: Duration) {
    current = current.plus(duration)
  }
}

class InMemorySessionStore_tests {
  private val heartbeatTimeout = Duration.ofMinutes(3)

  private fun newStore(
      clock: MutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z")),
  ): Pair<InMemorySessionStore, MutableClock> =
      InMemorySessionStore(clock = clock, heartbeatTimeout = heartbeatTimeout) to clock

  @Test
  fun `abort transitions a running session to aborted and fences further worker writes`() =
      runBlocking {
        val (store, _) = newStore()
        store.create(
            repoFullName = "acme/app",
            taskMarkdown = "# Task",
            createdBy = "u@x",
            engine = Engine.Unspecified,
        )
        val running = store.claimNext()!!
        assertEquals(SessionState.Running, running.state)

        // Abort applies while RUNNING.
        assertIs<GuardedResult.Applied<Unit>>(store.abort(running.id))
        assertEquals(SessionState.Aborted, store.get(running.id, afterSeq = 0)!!.session.state)

        // The worker's signal writes now report Aborted (its cue to stop) — not a caller error —
        // and don't mutate the session; and aborting again is idempotent.
        assertEquals(GuardedResult.Aborted, store.heartbeat(running.id))
        assertEquals(
            GuardedResult.Aborted,
            store.appendEvent(running.id, SessionEventKind.AgentAction, "late event"),
        )
        assertEquals(GuardedResult.Aborted, store.abort(running.id))
        assertEquals(SessionState.Aborted, store.get(running.id, afterSeq = 0)!!.session.state)
      }

  @Test
  fun `abort on a non-running session is a precondition failure`() = runBlocking {
    val (store, _) = newStore()
    val pending =
        store.create(
            repoFullName = "acme/app",
            taskMarkdown = "# Task",
            createdBy = "u@x",
            engine = Engine.Unspecified,
        )
    assertEquals(GuardedResult.PreconditionFailed, store.abort(pending.id))
  }

  @Test
  fun `create yields a pending session that list returns`() = runBlocking {
    val (store, _) = newStore()

    val session =
        store.create(
            repoFullName = "acme/app",
            taskMarkdown = "# Task",
            createdBy = "u@x",
            engine = Engine.Unspecified,
        )

    assertEquals(SessionState.Pending, session.state)
    assertEquals("acme/app", session.repoFullName)
    assertEquals("u@x", session.createdBy)
    assertNull(session.claimedAt)
    assertNull(session.prUrl)

    assertEquals(listOf(session.id), store.list(limit = 100).map { it.id })
  }

  @Test
  fun `list returns sessions newest-first and honours the limit`() = runBlocking {
    val (store, clock) = newStore()

    val first = store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    clock.advance(Duration.ofSeconds(1))
    val second = store.create("acme/b", "t", "u@x", engine = Engine.Unspecified)
    clock.advance(Duration.ofSeconds(1))
    val third = store.create("acme/c", "t", "u@x", engine = Engine.Unspecified)

    assertEquals(listOf(third.id, second.id, first.id), store.list(limit = 100).map { it.id })
    assertEquals(listOf(third.id, second.id), store.list(limit = 2).map { it.id })
  }

  @Test
  fun `claimNext returns null on an empty queue`() = runBlocking {
    val (store, _) = newStore()

    assertNull(store.claimNext())
  }

  @Test
  fun `claimNext claims oldest first and never returns the same session twice`() = runBlocking {
    val (store, clock) = newStore()

    val first = store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    clock.advance(Duration.ofSeconds(1))
    val second = store.create("acme/b", "t", "u@x", engine = Engine.Unspecified)

    val firstClaim = store.claimNext()
    val secondClaim = store.claimNext()

    assertEquals(first.id, firstClaim?.id)
    assertEquals(SessionState.Running, firstClaim?.state)
    assertEquals(second.id, secondClaim?.id)
    assertNotEquals(firstClaim?.id, secondClaim?.id)

    // Queue now empty — everything is RUNNING.
    assertNull(store.claimNext())
  }

  @Test
  fun `claimNext claims the oldest PENDING regardless of engine (uniform workers)`() = runBlocking {
    val (store, clock) = newStore()

    val claude = store.create("acme/a", "t", "u@x", engine = Engine.Claude)
    clock.advance(Duration.ofSeconds(1))
    store.create("acme/b", "t", "u@x", engine = Engine.Builtin)

    // Every worker runs every engine, so the claim is unconditional: oldest first, even if CLAUDE.
    val claim = store.claimNext()
    assertEquals(claude.id, claim?.id)
  }

  @Test
  fun `worker mutations require a running session`() = runBlocking {
    val (store, _) = newStore()

    val pending = store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)

    // Not yet claimed → still PENDING → all guarded mutations refuse.
    assertPreconditionFailed(store.appendEvent(pending.id, SessionEventKind.ScoutingRound, "hi"))
    assertPreconditionFailed(store.heartbeat(pending.id))
    assertPreconditionFailed(store.complete(pending.id, "https://pr"))
    assertPreconditionFailed(store.fail(pending.id, "nope"))

    // Unknown id → precondition failure too.
    assertPreconditionFailed(store.heartbeat(SessionId("missing")))
  }

  @Test
  fun `completing then mutating again is a no-op precondition failure`() = runBlocking {
    val (store, _) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    assertApplied(store.complete(claimed.id, "https://pr/1"))

    val completed = store.get(claimed.id, afterSeq = 0)!!.session
    assertEquals(SessionState.Completed, completed.state)
    assertEquals("https://pr/1", completed.prUrl)

    // Terminal state is immutable through the store API.
    assertPreconditionFailed(store.complete(claimed.id, "https://pr/2"))
    assertPreconditionFailed(store.fail(claimed.id, "too late"))
    assertPreconditionFailed(store.heartbeat(claimed.id))
    assertPreconditionFailed(store.appendEvent(claimed.id, SessionEventKind.Publishing, "x"))

    val stillCompleted = store.get(claimed.id, afterSeq = 0)!!.session
    assertEquals(SessionState.Completed, stillCompleted.state)
    assertEquals("https://pr/1", stillCompleted.prUrl)
  }

  @Test
  fun `failing a running session records the summary and locks it`() = runBlocking {
    val (store, _) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    assertApplied(store.fail(claimed.id, "boom"))

    val failed = store.get(claimed.id, afterSeq = 0)!!.session
    assertEquals(SessionState.Failed, failed.state)
    assertEquals("boom", failed.failureSummary)

    assertPreconditionFailed(store.complete(claimed.id, "https://pr"))
  }

  @Test
  fun `appendEvent assigns increasing seq and get filters by afterSeq`() = runBlocking {
    val (store, _) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    val e1 = assertApplied(store.appendEvent(claimed.id, SessionEventKind.ScoutingRound, "a"))
    val e2 = assertApplied(store.appendEvent(claimed.id, SessionEventKind.HealthCheck, "b"))

    assertEquals(1, e1.seq)
    assertEquals(2, e2.seq)

    assertEquals(listOf(1, 2), store.get(claimed.id, afterSeq = 0)!!.events.map { it.seq })
    assertEquals(listOf(2), store.get(claimed.id, afterSeq = 1)!!.events.map { it.seq })
    assertTrue(store.get(claimed.id, afterSeq = 2)!!.events.isEmpty())
  }

  @Test
  fun `appendEvent truncates over-long messages`() = runBlocking {
    val (store, _) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    val huge = "x".repeat(SessionStore.maxEventMessageLength + 500)
    val event = assertApplied(store.appendEvent(claimed.id, SessionEventKind.ScoutingRound, huge))

    assertEquals(SessionStore.maxEventMessageLength, event.message.length)
  }

  @Test
  fun `appendEvent and heartbeat refresh the heartbeat so expiry does not fire`() = runBlocking {
    val (store, clock) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    // Almost at the timeout, then a heartbeat resets the clock-of-death.
    clock.advance(heartbeatTimeout.minusSeconds(10))
    assertApplied(store.heartbeat(claimed.id))
    clock.advance(heartbeatTimeout.minusSeconds(10))

    assertEquals(0, store.expireStale())
    assertEquals(SessionState.Running, store.get(claimed.id, afterSeq = 0)!!.session.state)
  }

  @Test
  fun `a running session with a stale heartbeat is lazily expired on read`() = runBlocking {
    val (store, clock) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val claimed = store.claimNext()!!

    clock.advance(heartbeatTimeout.plusSeconds(1))

    // The read itself performs expiry.
    val read = store.get(claimed.id, afterSeq = 0)!!.session
    assertEquals(SessionState.Failed, read.state)
    assertEquals(SessionStore.workerLostSummary, read.failureSummary)

    // And an expired session refuses further worker mutations.
    assertPreconditionFailed(store.heartbeat(claimed.id))
  }

  @Test
  fun `expireStale only affects sessions past the timeout`() = runBlocking {
    val (store, clock) = newStore()

    store.create("acme/a", "t", "u@x", engine = Engine.Unspecified)
    val staleClaim = store.claimNext()!!

    clock.advance(heartbeatTimeout.plusSeconds(1))

    store.create("acme/b", "t", "u@x", engine = Engine.Unspecified)
    val freshClaim = store.claimNext()!!

    assertEquals(1, store.expireStale())
    assertEquals(SessionState.Failed, store.get(staleClaim.id, afterSeq = 0)!!.session.state)
    assertEquals(SessionState.Running, store.get(freshClaim.id, afterSeq = 0)!!.session.state)
  }

  @Test
  fun `get returns null for an unknown session`() = runBlocking {
    val (store, _) = newStore()

    assertNull(store.get(SessionId("nope"), afterSeq = 0))
  }

  private fun <T> assertApplied(
      result: GuardedResult<T>,
  ): T {
    assertTrue(result is GuardedResult.Applied, "expected Applied but was $result")
    return result.value
  }

  private fun assertPreconditionFailed(
      result: GuardedResult<*>,
  ) {
    assertEquals(GuardedResult.PreconditionFailed, result)
  }
}
