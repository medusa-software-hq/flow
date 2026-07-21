package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * [InMemoryWorkerStore] behaviour the fleet-registry / liveness logic depends on: an upsert that
 * preserves `first_seen_at` while moving `last_seen_at`, and a freshest-first ordering.
 */
class InMemoryWorkerStore_tests {
  /** A clock whose instant the test advances between calls, to drive last-seen recency. */
  private class MutableClock(
      var now: Instant,
  ) : Clock() {
    override fun instant(): Instant = now

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?): Clock = this
  }

  @Test
  fun `re-registering a worker preserves first_seen and advances last_seen`() = runBlocking {
    val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))
    val store = InMemoryWorkerStore(clock)

    store.register("w1", "1.0.0", "", listOf(Engine.Builtin))
    val firstSeen = store.list().single().firstSeenAt

    clock.now = clock.now.plus(Duration.ofSeconds(30))
    store.register("w1", "1.1.0", "sha256:xyz", listOf(Engine.Claude))

    val worker = store.list().single()
    assertEquals(firstSeen, worker.firstSeenAt)
    assertEquals(clock.now, worker.lastSeenAt)
    assertEquals("1.1.0", worker.workerVersion)
    assertEquals("sha256:xyz", worker.imageDigest)
    assertEquals(listOf(Engine.Claude), worker.supportedEngines)
  }

  @Test
  fun `list returns workers freshest first`() = runBlocking {
    val clock = MutableClock(Instant.parse("2026-07-21T00:00:00Z"))
    val store = InMemoryWorkerStore(clock)

    store.register("old", "1.0.0", "", emptyList())
    clock.now = clock.now.plus(Duration.ofSeconds(10))
    store.register("new", "1.0.0", "", emptyList())

    assertEquals(listOf("new", "old"), store.list().map { it.workerId })
  }
}
