package software.medusa.flow.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.flow.db.FlowDatabase
import software.medusa.flow.db.Session_events
import software.medusa.flow.db.Sessions
import software.medusa.flow.server.SessionStore.Companion.truncateMessage
import software.medusa.flow.server.SessionStore.Companion.workerLostSummary

/**
 * A Postgres-backed [SessionStore] (SQLDelight queries over the Flyway-owned schema). Timestamps
 * are stored as `TIMESTAMPTZ` and exposed as [Instant].
 *
 * [clock] and [heartbeatTimeout] are injectable to mirror the in-memory store; the store applies
 * timestamps from [clock] rather than the database's `now()` so both implementations behave
 * identically.
 */
class PostgresSessionStore(
    private val database: FlowDatabase,
    private val clock: Clock = Clock.systemUTC(),
    private val heartbeatTimeout: Duration = InMemorySessionStore.defaultHeartbeatTimeout,
) : SessionStore {
  private val queries = database.sessionQueries

  override suspend fun create(
      repoFullName: String,
      taskMarkdown: String,
      createdBy: String,
      engine: Engine,
  ): Session =
      withContext(Dispatchers.IO) {
        insertSessionRow(
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
      withContext(Dispatchers.IO) {
        val jobId = JobId(UUID.randomUUID().toString())
        database.transactionWithResult {
          engines.map { insertSessionRow(jobId, repoFullName, taskMarkdown, createdBy, it) }
        }
      }

  private fun insertSessionRow(
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
            createdAt = clock.instant(),
            createdBy = createdBy,
            claimedAt = null,
            lastHeartbeatAt = null,
            prUrl = null,
            failureSummary = null,
            engine = engine,
        )

    queries.insertSession(
        id = session.id.id,
        job_id = jobId.id,
        repo_full_name = repoFullName,
        task_markdown = taskMarkdown,
        state = session.state.toDbValue(),
        created_at = session.createdAt.toOffsetDateTime(),
        created_by = createdBy,
        engine = engine.toDbValue(),
    )

    return session
  }

  override suspend fun list(
      limit: Int,
  ): List<Session> =
      withContext(Dispatchers.IO) {
        expireStaleBlocking()

        queries.listSessions(limit.toLong()).executeAsList().map { it.toDomain() }
      }

  override suspend fun get(
      id: SessionId,
      afterSeq: Int,
  ): SessionWithEvents? =
      withContext(Dispatchers.IO) {
        expireStaleBlocking()

        val session =
            queries.selectSessionById(id.id).executeAsOneOrNull()?.toDomain()
                ?: return@withContext null

        val events =
            queries.selectEventsAfterSeq(session_id = id.id, seq = afterSeq).executeAsList().map {
              it.toDomain()
            }

        SessionWithEvents(session = session, events = events)
      }

  override suspend fun claimNext(): Session? =
      withContext(Dispatchers.IO) {
        val now = clock.instant().toOffsetDateTime()
        // Uniform workers: any worker can run any engine, so the claim is unconditional.
        queries.claimNextSession(now = now).executeAsOneOrNull()?.toDomain()
      }

  override suspend fun claimNextJob(): List<Session> =
      withContext(Dispatchers.IO) {
        val now = clock.instant().toOffsetDateTime()
        // Claims every PENDING session of the oldest pending job in one guarded statement.
        queries.claimNextJob(now = now).executeAsList().map { it.toDomain() }
      }

  override suspend fun appendEvent(
      id: SessionId,
      kind: SessionEventKind,
      message: String,
      costUsd: Double?,
  ): GuardedResult<SessionEvent> =
      withContext(Dispatchers.IO) {
        val now = clock.instant()
        val truncated = truncateMessage(message)

        database.transactionWithResult {
          // Doubles as the RUNNING guard and the heartbeat bump.
          queries
              .touchHeartbeatIfRunning(now = now.toOffsetDateTime(), id = id.id)
              .executeAsOneOrNull() ?: return@transactionWithResult abortedOrPreconditionFailed(id)

          val seq = queries.selectMaxEventSeq(session_id = id.id).executeAsOne() + 1

          queries.insertEvent(
              session_id = id.id,
              seq = seq,
              created_at = now.toOffsetDateTime(),
              kind = kind.name,
              message = truncated,
          )

          // Stamp cost onto the session row (RunCost events only) so the list can show it.
          if (costUsd != null) {
            queries.stampCostIfRunning(cost = costUsd, now = now.toOffsetDateTime(), id = id.id)
          }

          GuardedResult.Applied(
              SessionEvent(seq = seq, createdAt = now, kind = kind, message = truncated),
          )
        }
      }

  override suspend fun heartbeat(
      id: SessionId,
  ): GuardedResult<Unit> =
      withContext(Dispatchers.IO) {
        val applied =
            queries
                .touchHeartbeatIfRunning(now = clock.instant().toOffsetDateTime(), id = id.id)
                .executeAsOneOrNull()
        if (applied != null) GuardedResult.Applied(Unit) else abortedOrPreconditionFailed(id)
      }

  override suspend fun abort(
      id: SessionId,
  ): GuardedResult<Unit> =
      withContext(Dispatchers.IO) {
        val applied =
            queries
                .abortIfRunning(now = clock.instant().toOffsetDateTime(), id = id.id)
                .executeAsOneOrNull()
        // Applied when it was RUNNING; already-ABORTED -> Aborted; otherwise PreconditionFailed.
        if (applied != null) GuardedResult.Applied(Unit) else abortedOrPreconditionFailed(id)
      }

  override suspend fun complete(
      id: SessionId,
      prUrl: String,
  ): GuardedResult<Unit> =
      withContext(Dispatchers.IO) {
        queries
            .completeIfRunning(pr_url = prUrl, now = clock.instant().toOffsetDateTime(), id = id.id)
            .executeAsOneOrNull()
            .toGuardedUnit()
      }

  override suspend fun fail(
      id: SessionId,
      failureSummary: String,
  ): GuardedResult<Unit> =
      withContext(Dispatchers.IO) {
        queries
            .failIfRunning(
                failure_summary = failureSummary,
                now = clock.instant().toOffsetDateTime(),
                id = id.id,
            )
            .executeAsOneOrNull()
            .toGuardedUnit()
      }

  override suspend fun expireStale(): Int = withContext(Dispatchers.IO) { expireStaleBlocking() }

  private fun expireStaleBlocking(): Int {
    val cutoff = clock.instant().minus(heartbeatTimeout)

    return queries
        .expireStaleSessions(
            failure_summary = workerLostSummary,
            last_heartbeat_at = cutoff.toOffsetDateTime(),
        )
        .executeAsList()
        .size
  }

  private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

  private fun SessionState.toDbValue(): String =
      when (this) {
        SessionState.Pending -> "PENDING"
        SessionState.Running -> "RUNNING"
        SessionState.Completed -> "COMPLETED"
        SessionState.Failed -> "FAILED"
        SessionState.Aborted -> "ABORTED"
      }

  private fun parseState(
      value: String,
  ): SessionState =
      when (value) {
        "PENDING" -> SessionState.Pending
        "RUNNING" -> SessionState.Running
        "COMPLETED" -> SessionState.Completed
        "FAILED" -> SessionState.Failed
        "ABORTED" -> SessionState.Aborted
        else -> error("Unknown session state: $value")
      }

  /**
   * After a guarded `…IfRunning` write finds no RUNNING row: [GuardedResult.Aborted] if the session
   * is now ABORTED (the worker's stop cue, not its fault), else [GuardedResult.PreconditionFailed].
   */
  private fun abortedOrPreconditionFailed(
      id: SessionId,
  ): GuardedResult<Nothing> =
      if (
          queries.selectSessionState(id.id).executeAsOneOrNull() == SessionState.Aborted.toDbValue()
      ) {
        GuardedResult.Aborted
      } else {
        GuardedResult.PreconditionFailed
      }

  private fun Engine.toDbValue(): String =
      when (this) {
        Engine.Unspecified -> "UNSPECIFIED"
        Engine.Builtin -> "BUILTIN"
        Engine.Claude -> "CLAUDE"
      }

  private fun parseEngine(
      value: String,
  ): Engine =
      when (value) {
        "UNSPECIFIED" -> Engine.Unspecified
        "BUILTIN" -> Engine.Builtin
        "CLAUDE" -> Engine.Claude
        else -> error("Unknown engine: $value")
      }

  private fun Sessions.toDomain(): Session =
      Session(
          id = SessionId(id),
          jobId = JobId(job_id),
          repoFullName = repo_full_name,
          taskMarkdown = task_markdown,
          state = parseState(state),
          createdAt = created_at.toInstant(),
          createdBy = created_by,
          claimedAt = claimed_at?.toInstant(),
          lastHeartbeatAt = last_heartbeat_at?.toInstant(),
          prUrl = pr_url,
          failureSummary = failure_summary,
          engine = parseEngine(engine),
          totalCostUsd = total_cost_usd,
      )

  private fun Session_events.toDomain(): SessionEvent =
      SessionEvent(
          seq = seq,
          createdAt = created_at.toInstant(),
          kind = SessionEventKind.valueOf(kind),
          message = message,
      )

  private fun String?.toGuardedUnit(): GuardedResult<Unit> =
      if (this == null) GuardedResult.PreconditionFailed else GuardedResult.Applied(Unit)
}
