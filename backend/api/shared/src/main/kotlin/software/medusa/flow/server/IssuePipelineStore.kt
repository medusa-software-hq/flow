package software.medusa.flow.server

import kotlin.time.Instant

@JvmInline
value class IssuePipelineId(
    val id: String,
)

/**
 * The persisted state of an issue pipeline. See `plan/m2/design/01-issue-pipeline.md`.
 *
 * ```
 * IN_PROGRESS → PR_OPEN → AWAITING_MERGE_CHECKS → DONE
 *      └──────────┴─────────────┴──────────────→ FAILED
 * ```
 *
 * `DONE` is terminal; `FAILED` is terminal-until-cleared.
 */
enum class IssuePipelineState {
  InProgress,
  PrOpen,
  AwaitingMergeChecks,
  Done,
  Failed,
}

/** One picked `(repo, issue)` run through the session pipeline. */
data class IssuePipeline(
    val id: IssuePipelineId,
    val repoFullName: String,
    val issueNumber: Int,
    val issueTitle: String,
    val issueUrl: String,
    val state: IssuePipelineState,
    val sessionId: SessionId?,
    /**
     * The built-in "shadow" session run in parallel for comparison (M6 dual-engine). It opens its
     * own PR but is never observed — it doesn't advance this pipeline's state or gate the merge,
     * and its PR is closed manually. Null for pipelines picked before dual-engine fan-out.
     */
    val shadowSessionId: SessionId?,
    val prNumber: Int?,
    val prUrl: String?,
    val mergeCommitSha: String?,
    val failureSummary: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val clearedAt: Instant?,
) {
  /**
   * A live pipeline holds its repo's mutex: an active state, or a `FAILED` row that hasn't been
   * cleared (FAILED holds the mutex — the deliberate maximum-caution starting point).
   */
  val isLive: Boolean
    get() =
        when (state) {
          IssuePipelineState.InProgress,
          IssuePipelineState.PrOpen,
          IssuePipelineState.AwaitingMergeChecks -> true
          IssuePipelineState.Failed -> clearedAt == null
          IssuePipelineState.Done -> false
        }
}

/** Outcome of [IssuePipelineStore.pick]. */
sealed interface PickResult {
  data class Picked(
      val pipeline: IssuePipeline,
  ) : PickResult

  /** The repo already has a live pipeline; the pick was a no-op. */
  data object RepoBusy : PickResult
}

/**
 * Outcome of a guarded state transition. A transition that doesn't apply against the pipeline's
 * current state (absent, or wrong source state) yields [Rejected] and changes nothing — the service
 * layer surfaces that as `FAILED_PRECONDITION`, mirroring M1's [GuardedResult].
 */
sealed interface PipelineTransition {
  data class Applied(
      val pipeline: IssuePipeline,
  ) : PipelineTransition

  data class Rejected(
      val reason: String,
  ) : PipelineTransition
}

/**
 * Storage for issue pipelines, enforcing the state machine and the repo mutex at the store layer —
 * the M2 counterpart of [SessionStore]. Every transition with a GitHub side effect writes its
 * outbox entries (via the shared [GithubOutboxStore]) in the same transaction as the state change.
 */
interface IssuePipelineStore {
  /**
   * Atomically starts a pipeline for `(repoFullName, issueNumber)` if the repo is free: inserts an
   * `IN_PROGRESS` row linked to [sessionId] (and [shadowSessionId], when the caller fans out a
   * built-in shadow session — currently disabled, see [shadowSessionId]) and enqueues the
   * `flow:in-progress` label. Returns [PickResult.RepoBusy] without changing anything if the repo
   * already has a live pipeline. The SQL uniqueness indexes make a double-pick impossible even
   * under a race.
   */
  suspend fun pick(
      repoFullName: String,
      issueNumber: Int,
      issueTitle: String,
      issueUrl: String,
      sessionId: SessionId,
      shadowSessionId: SessionId?,
  ): PickResult

  /** `IN_PROGRESS → PR_OPEN`. Enqueues the label swap `flow:in-progress` → `flow:pr-open`. */
  suspend fun markPrOpen(
      id: IssuePipelineId,
      prNumber: Int,
      prUrl: String,
  ): PipelineTransition

  /** `PR_OPEN → AWAITING_MERGE_CHECKS`. No label change (`flow:pr-open` covers both). */
  suspend fun markAwaitingMergeChecks(
      id: IssuePipelineId,
      mergeCommitSha: String,
  ): PipelineTransition

  /**
   * `AWAITING_MERGE_CHECKS → DONE`. Enqueues: remove `flow:pr-open`, post [annotationMarkdown], and
   * close the issue (the graph-advancing action).
   */
  suspend fun markDone(
      id: IssuePipelineId,
      annotationMarkdown: String,
  ): PipelineTransition

  /**
   * Any live state `→ FAILED`. Enqueues: remove the current state's label, add `flow:failed`, and
   * post [failureSummary] as an annotation comment. The row keeps holding the repo mutex until
   * cleared.
   */
  suspend fun markFailed(
      id: IssuePipelineId,
      failureSummary: String,
  ): PipelineTransition

  /**
   * `FAILED → cleared` (human action). Sets `cleared_at`, releasing the mutex, and enqueues removal
   * of `flow:failed`. A cleared row is dead; a re-pick creates a new row.
   */
  suspend fun clear(
      id: IssuePipelineId,
  ): PipelineTransition

  /** The pipeline [id], or null if absent. */
  suspend fun get(
      id: IssuePipelineId,
  ): IssuePipeline?

  /**
   * The pipeline [sessionId] participates in — as either the primary or the shadow session — or
   * null for a manual/unlinked session. Callers that must act only on behalf of the *primary*
   * session (e.g. advancing pipeline state) must check `pipeline.sessionId == sessionId`
   * themselves; this lookup deliberately also matches the shadow so display enrichment works for
   * both.
   */
  suspend fun findBySessionId(
      sessionId: SessionId,
  ): IssuePipeline?

  /** Every live pipeline (the ones holding a repo mutex), across all repos. */
  suspend fun listLive(): List<IssuePipeline>

  /** Pipelines newest-first, optionally filtered to one repo. */
  suspend fun list(
      repoFullName: String?,
  ): List<IssuePipeline>

  /** Whether [repoFullName] currently has a live pipeline. */
  suspend fun isRepoBusy(
      repoFullName: String,
  ): Boolean

  companion object {
    /** GitHub label projected from pipeline state. */
    const val labelInProgress = "flow:in-progress"
    const val labelPrOpen = "flow:pr-open"
    const val labelFailed = "flow:failed"

    /** The `flow:*` label an active pipeline projects, or null when it projects none. */
    fun labelFor(
        state: IssuePipelineState,
    ): String? =
        when (state) {
          IssuePipelineState.InProgress -> labelInProgress
          IssuePipelineState.PrOpen,
          IssuePipelineState.AwaitingMergeChecks -> labelPrOpen
          IssuePipelineState.Failed -> labelFailed
          IssuePipelineState.Done -> null
        }
  }
}
