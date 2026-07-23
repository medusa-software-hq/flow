package software.medusa.flow.server

import java.time.Instant

@JvmInline
value class SessionId(
    val id: String,
)

/** The persisted lifecycle state of a session. See `design/01-architecture.md`. */
enum class SessionState {
  Pending,
  Running,
  Completed,
  Failed,
  Aborted,
}

/**
 * The agentic engine that runs a session (M4). [Unspecified] means "the claiming worker's default";
 * such a session may be claimed by any worker. A named engine may only be claimed by a worker that
 * declares it in its supported set (see [SessionStore.claimNext]).
 */
enum class Engine {
  Unspecified,
  Builtin,
  Claude,
}

/** The kind of a display-only progress event. Mirrors the proto `SessionEventKind`. */
enum class SessionEventKind {
  WorkspacePreparing,
  HealthGate,
  ScoutingRound,
  WorkspaceBriefing,
  ImplementationPlanning,
  ImplementationAttempt,
  HealthCheck,
  Publishing,
  // M4 (Claude engine): live narrative / tool actions, the opening engine banner, the run cost.
  AgentAction,
  EngineBanner,
  RunCost,
}

/** A session row, toolchain- and transport-agnostic (no proto types here). */
data class Session(
    val id: SessionId,
    val repoFullName: String,
    val taskMarkdown: String,
    val state: SessionState,
    val createdAt: Instant,
    val createdBy: String,
    val claimedAt: Instant?,
    val lastHeartbeatAt: Instant?,
    val prUrl: String?,
    val failureSummary: String?,
    val engine: Engine,
    /** Display-only terminal cost in USD; null until the run's RUN_COST event lands. */
    val totalCostUsd: Double? = null,
)

/** A single append-only display event belonging to a session. */
data class SessionEvent(
    val seq: Int,
    val createdAt: Instant,
    val kind: SessionEventKind,
    val message: String,
)

/** A session together with a (possibly filtered) slice of its events. */
data class SessionWithEvents(
    val session: Session,
    val events: List<SessionEvent>,
)

/**
 * Outcome of a worker mutation that is only valid against a `RUNNING` session.
 *
 * The state machine (`PENDING → RUNNING → COMPLETED | FAILED`) is enforced here: a mutation targets
 * a session that is absent or no longer `RUNNING` yields [PreconditionFailed] and changes nothing,
 * which the service layer surfaces as `FAILED_PRECONDITION`.
 */
sealed interface GuardedResult<out T> {
  data class Applied<out T>(
      val value: T,
  ) : GuardedResult<T>

  /**
   * The write targeted a session that has been ABORTED. This is *not* a caller error — a worker
   * can't know its session was aborted out from under it — so it is a distinct, expected outcome
   * (surfaced to the worker as the `ABORTED` write-ack, its cue to stop), never a
   * `FAILED_PRECONDITION`.
   */
  data object Aborted : GuardedResult<Nothing>

  data object PreconditionFailed : GuardedResult<Nothing>
}

/**
 * Storage for sessions and their display events (a store interface with a Postgres impl and an
 * in-memory fake).
 *
 * Reads ([list], [get]) perform lazy heartbeat expiry first: any `RUNNING` session whose heartbeat
 * has aged past the store's configured timeout is transitioned to `FAILED` ("worker lost") before
 * the read returns, so no background scheduler is needed.
 */
interface SessionStore {
  /** Creates a new `PENDING` session and returns it. */
  suspend fun create(
      repoFullName: String,
      taskMarkdown: String,
      createdBy: String,
      engine: Engine,
  ): Session

  /** Returns the newest [limit] sessions, newest first, after expiring stale ones. */
  suspend fun list(
      limit: Int,
  ): List<Session>

  /**
   * Returns the session [id] with the events whose `seq` is greater than [afterSeq] (for
   * incremental polling), after expiring stale sessions; null when the session does not exist.
   */
  suspend fun get(
      id: SessionId,
      afterSeq: Int,
  ): SessionWithEvents?

  /**
   * Atomically claims the oldest `PENDING` session, moving it to `RUNNING`; null when nothing is
   * claimable. Two concurrent claims never return the same session. Workers are uniform (every
   * worker runs every engine), so a claim is unconditional — engine no longer gates it.
   */
  suspend fun claimNext(): Session?

  /**
   * Appends a display event to a `RUNNING` session, assigning the next per-session `seq`, capping
   * the message length, and bumping the heartbeat. When [costUsd] is non-null (a `RunCost` event)
   * it is also stamped onto the session's [Session.totalCostUsd] so the list can show cost without
   * loading events.
   */
  suspend fun appendEvent(
      id: SessionId,
      kind: SessionEventKind,
      message: String,
      costUsd: Double? = null,
  ): GuardedResult<SessionEvent>

  /** Bumps the heartbeat of a `RUNNING` session. */
  suspend fun heartbeat(
      id: SessionId,
  ): GuardedResult<Unit>

  /** Transitions a `RUNNING` session to `COMPLETED` with its PR URL. */
  suspend fun complete(
      id: SessionId,
      prUrl: String,
  ): GuardedResult<Unit>

  /** Transitions a `RUNNING` session to `FAILED` with a human-readable Markdown summary. */
  suspend fun fail(
      id: SessionId,
      failureSummary: String,
  ): GuardedResult<Unit>

  /**
   * Transitions a `RUNNING` session to `ABORTED` (the human "stop"). Idempotent-ish: an
   * already-`ABORTED` session returns [GuardedResult.Aborted]; any other non-`RUNNING` state (or a
   * missing session) returns [GuardedResult.PreconditionFailed].
   */
  suspend fun abort(
      id: SessionId,
  ): GuardedResult<Unit>

  /**
   * Transitions every `RUNNING` session whose heartbeat is older than the configured timeout to
   * `FAILED` ("worker lost"); returns how many were expired. Invoked implicitly by the reads, but
   * exposed for explicit sweeps too.
   */
  suspend fun expireStale(): Int

  companion object {
    /**
     * Maximum stored event message length; longer messages are truncated (see `design/02-api.md`).
     */
    const val maxEventMessageLength = 4096

    /**
     * Display-only cap on how many `AgentAction` events one session may emit (M4 claude engine). A
     * chatty tool stream is coalesced against this by the worker's reporting adapter so event
     * volume stays bounded; the phase/banner/cost events are never counted against it.
     */
    const val maxAgentActionEvents = 200

    /** Marker summary written when a session is expired for a lost heartbeat. */
    const val workerLostSummary = "Worker lost"

    fun truncateMessage(
        message: String,
    ): String =
        if (message.length <= maxEventMessageLength) message
        else message.take(maxEventMessageLength)
  }
}
