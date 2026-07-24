package software.medusa.flow.server

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
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
    private val clock: Clock = Clock.System,
    private val heartbeatTimeout: Duration = defaultHeartbeatTimeout,
) : SessionStore {
  companion object {
    val defaultHeartbeatTimeout: Duration = 3.minutes
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
        insertSessionLocked(
            JobId(UUID.randomUUID().toString()),
            repoFullName,
            taskMarkdown,
            createdBy,
            engine,
        )
      }

  override suspend fun createJob(
      repoFullName: String,
      taskMarkdown: String,
      createdBy: String,
      engines: List<Engine>,
  ): List<Session> =
      synchronized(lock) {
        val jobId = JobId(UUID.randomUUID().toString())
        engines.map { engine ->
          insertSessionLocked(jobId, repoFullName, taskMarkdown, createdBy, engine)
        }
      }

  private fun insertSessionLocked(
      jobId: JobId,
      repoFullName: String,
      taskMarkdown: String,
      createdBy: String,
      engine: Engine,
  ): Session {
    val session =
        Session(
            id = SessionId(UUID.randomUUID().toString()),
            jobId = jobId,
            repoFullName = repoFullName,
            taskMarkdown = taskMarkdown,
            state = SessionState.Pending,
            createdAt = clock.now(),
            createdBy = createdBy,
            claimedAt = null,
            lastHeartbeatAt = null,
            prUrl = null,
            failureSummary = null,
            engine = engine,
        )
    sessionsById[session.id] = session
    return session
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

        val now = clock.now()

        val claimed =
            oldestPending.copy(
                state = SessionState.Running,
                claimedAt = now,
                lastHeartbeatAt = now,
            )

        sessionsById[claimed.id] = claimed

        claimed
      }

  override suspend fun claimNextJob(): List<Session> =
      synchronized(lock) {
        val oldestPending =
            sessionsById.values
                .filter { it.state == SessionState.Pending }
                .minByOrNull { it.createdAt } ?: return@synchronized emptyList()

        val now = clock.now()
        // Snapshot the job's pending sessions before mutating the map. Insertion order is
        // preserved,
        // so the primary (created first) comes before the shadow.
        val jobPending =
            sessionsById.values
                .filter { it.state == SessionState.Pending && it.jobId == oldestPending.jobId }
                .toList()

        jobPending.map { pending ->
          val claimed =
              pending.copy(state = SessionState.Running, claimedAt = now, lastHeartbeatAt = now)
          sessionsById[claimed.id] = claimed
          claimed
        }
      }

  override suspend fun appendEvent(
      id: SessionId,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ): GuardedResult<SessionEvent> =
      synchronized(lock) {
        val running =
            when (val guard = writableSessionOrGuard(id)) {
              is GuardedResult.Applied -> guard.value
              GuardedResult.Aborted -> return@synchronized GuardedResult.Aborted
              GuardedResult.PreconditionFailed ->
                  return@synchronized GuardedResult.PreconditionFailed
            }

        val events = eventsBySessionId.getOrPut(id) { mutableListOf() }

        val event =
            SessionEvent(
                seq = (events.maxOfOrNull { it.seq } ?: 0) + 1,
                createdAt = clock.now(),
                kind = kind,
                message = truncateMessage(message),
            )

        events += event

        sessionsById[id] =
            running.copy(
                lastHeartbeatAt = clock.now(),
                totalCostUsd = costUsd ?: running.totalCostUsd,
            )

        GuardedResult.Applied(event)
      }

  override suspend fun heartbeat(
      id: SessionId,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running =
            when (val guard = writableSessionOrGuard(id)) {
              is GuardedResult.Applied -> guard.value
              GuardedResult.Aborted -> return@synchronized GuardedResult.Aborted
              GuardedResult.PreconditionFailed ->
                  return@synchronized GuardedResult.PreconditionFailed
            }

        sessionsById[id] = running.copy(lastHeartbeatAt = clock.now())

        GuardedResult.Applied(Unit)
      }

  override suspend fun complete(
      id: SessionId,
      prUrl: String,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running =
            when (val guard = writableSessionOrGuard(id)) {
              is GuardedResult.Applied -> guard.value
              GuardedResult.Aborted -> return@synchronized GuardedResult.Aborted
              GuardedResult.PreconditionFailed ->
                  return@synchronized GuardedResult.PreconditionFailed
            }

        sessionsById[id] =
            running.copy(
                state = SessionState.Completed,
                prUrl = prUrl,
                lastHeartbeatAt = clock.now(),
            )

        GuardedResult.Applied(Unit)
      }

  override suspend fun fail(
      id: SessionId,
      failureSummary: String,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        val running =
            when (val guard = writableSessionOrGuard(id)) {
              is GuardedResult.Applied -> guard.value
              GuardedResult.Aborted -> return@synchronized GuardedResult.Aborted
              GuardedResult.PreconditionFailed ->
                  return@synchronized GuardedResult.PreconditionFailed
            }

        sessionsById[id] =
            running.copy(
                state = SessionState.Failed,
                failureSummary = failureSummary,
                lastHeartbeatAt = clock.now(),
            )

        GuardedResult.Applied(Unit)
      }

  override suspend fun abort(
      id: SessionId,
  ): GuardedResult<Unit> =
      synchronized(lock) {
        when (val session = sessionsById[id]) {
          null -> GuardedResult.PreconditionFailed
          else ->
              when (session.state) {
                SessionState.Running -> {
                  sessionsById[id] =
                      session.copy(
                          state = SessionState.Aborted,
                          lastHeartbeatAt = clock.now(),
                      )
                  GuardedResult.Applied(Unit)
                }
                // Already aborted — idempotent-ish; not a caller error.
                SessionState.Aborted -> GuardedResult.Aborted
                else -> GuardedResult.PreconditionFailed
              }
        }
      }

  /**
   * The guard shared by every worker-facing write: [GuardedResult.Applied] with the live session
   * while it's RUNNING, [GuardedResult.Aborted] once it's ABORTED (the worker's cue to stop, not an
   * error), and [GuardedResult.PreconditionFailed] for a missing session or any other terminal
   * state.
   */
  private fun writableSessionOrGuard(
      id: SessionId,
  ): GuardedResult<Session> =
      when (val session = sessionsById[id]) {
        null -> GuardedResult.PreconditionFailed
        else ->
            when (session.state) {
              SessionState.Running -> GuardedResult.Applied(session)
              SessionState.Aborted -> GuardedResult.Aborted
              else -> GuardedResult.PreconditionFailed
            }
      }

  override suspend fun expireStale(): Int = synchronized(lock) { expireStaleLocked() }

  private fun expireStaleLocked(): Int {
    val cutoff = clock.now().minus(heartbeatTimeout)

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
  ): Boolean = lastHeartbeatAt != null && lastHeartbeatAt < cutoff
}
