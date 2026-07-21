package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import software.medusa.flow.server.SessionStore.Companion.truncateMessage
import software.medusa.flow.server.SessionStore.Companion.workerLostSummary

/**
 * An in-memory [SessionStore] for tests and local runs. Enforces the same state-machine guards as
 * the Postgres store; not durable and not intended for production.
 *
 * A [Clock] and [heartbeatTimeout] are injectable so expiry and ordering are deterministic in
 * tests. All access is guarded by a single lock — enough for the single-worker M1 model and for the
 * store's non-blocking, purely in-memory operations.
 */
class InMemorySessionStore(
    private val clock: Clock = Clock.systemUTC(),
    private val heartbeatTimeout: Duration = defaultHeartbeatTimeout,
) : SessionStore {
  companion object {
    val defaultHeartbeatTimeout: Duration = Duration.ofMinutes(3)
  }

  private val lock = Any()

  // Insertion-ordered so that sessions sharing a created_at timestamp keep a deterministic order.
  private val sessionsById = LinkedHashMap<SessionId, Session>()
  private val eventsBySessionId = HashMap<SessionId, MutableList<SessionEvent>>()

  override suspend fun create(
      repoFullName: String,
      taskMarkdown: String,
      createdBy: String,
      engine: Engine,
  ): Session =
      synchronized(lock) {
        val session =
            Session(
                id = SessionId(UUID.randomUUID().toString()),
                repoFullName = repoFullName,
                taskMarkdown = taskMarkdown,
                state = SessionState.Pending,
                createdAt = clock.instant(),
                createdBy = createdBy,
                claimedAt = null,
                lastHeartbeatAt = null,
                prUrl = null,
                failureSummary = null,
                engine = engine,
            )

        sessionsById[session.id] = session

        session
      }

  override suspend fun list(
      limit: Int,
  ): List<Session> =
      synchronized(lock) {
        expireStaleLocked()

        sessionsById.values.sortedByDescending { it.createdAt }.take(limit)
      }

  override suspend fun get(
      id: SessionId,
      afterSeq: Int,
  ): SessionWithEvents? =
      synchronized(lock) {
        expireStaleLocked()

        val session = sessionsById[id] ?: return@synchronized null

        val events =
            eventsBySessionId[id].orEmpty().filter { it.seq > afterSeq }.sortedBy { it.seq }

        SessionWithEvents(session = session, events = events)
      }

  override suspend fun claimNext(): Session? =
      synchronized(lock) {
        val oldestPending =
            sessionsById.values
                .filter { it.state == SessionState.Pending }
                .minByOrNull { it.createdAt } ?: return@synchronized null

        val now = clock.instant()

        val claimed =
            oldestPending.copy(
                state = SessionState.Running,
                claimedAt = now,
                lastHeartbeatAt = now,
            )

        sessionsById[claimed.id] = claimed

        claimed
      }

  override suspend fun appendEvent(
      id: SessionId,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ): GuardedResult<SessionEvent> =
      synchronized(lock) {
        val running = runningOrNull(id) ?: return@synchronized GuardedResult.PreconditionFailed

        val events = eventsBySessionId.getOrPut(id) { mutableListOf() }

        val event =
            SessionEvent(
                seq = (events.maxOfOrNull { it.seq } ?: 0) + 1,
                createdAt = clock.instant(),
                kind = kind,
                message = truncateMessage(message),
            )

        events += event

        sessionsById[id] =
            running.copy(
                lastHeartbeatAt = clock.instant(),
                totalCostUsd = costUsd ?: running.totalCostUsd,
            )

        GuardedResult.Applied(event)
      }

  override suspend fun heartbeat(
      id: SessionId,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running = runningOrNull(id) ?: return@synchronized GuardedResult.PreconditionFailed

        sessionsById[id] = running.copy(lastHeartbeatAt = clock.instant())

        GuardedResult.Applied(Unit)
      }

  override suspend fun complete(
      id: SessionId,
      prUrl: String,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running = runningOrNull(id) ?: return@synchronized GuardedResult.PreconditionFailed

        sessionsById[id] =
            running.copy(
                state = SessionState.Completed,
                prUrl = prUrl,
                lastHeartbeatAt = clock.instant(),
            )

        GuardedResult.Applied(Unit)
      }

  override suspend fun fail(
      id: SessionId,
      failureSummary: String,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running = runningOrNull(id) ?: return@synchronized GuardedResult.PreconditionFailed

        sessionsById[id] =
            running.copy(
                state = SessionState.Failed,
                failureSummary = failureSummary,
                lastHeartbeatAt = clock.instant(),
            )

        GuardedResult.Applied(Unit)
      }

  override suspend fun expireStale(): Int = synchronized(lock) { expireStaleLocked() }

  private fun expireStaleLocked(): Int {
    val cutoff = clock.instant().minus(heartbeatTimeout)

    val staleSessions =
        sessionsById.values.filter { session ->
          session.state == SessionState.Running && session.isHeartbeatOlderThan(cutoff)
        }

    staleSessions.forEach { session ->
      sessionsById[session.id] =
          session.copy(state = SessionState.Failed, failureSummary = workerLostSummary)
    }

    return staleSessions.size
  }

  private fun runningOrNull(
      id: SessionId,
  ): Session? = sessionsById[id]?.takeIf { it.state == SessionState.Running }

  private fun Session.isHeartbeatOlderThan(
      cutoff: Instant,
  ): Boolean = lastHeartbeatAt != null && lastHeartbeatAt.isBefore(cutoff)
}
