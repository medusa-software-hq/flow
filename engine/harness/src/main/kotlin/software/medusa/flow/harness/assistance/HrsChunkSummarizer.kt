package software.medusa.flow.harness.assistance

import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind

/**
 * The continuation of a just-finished delegation thread, handed back alongside
 * [HrsAssistant.Result] so the executor can ask for a chunk summary at close (story 07) — no
 * separate compactor role: it is the same assistant, in the same thread, one extra structured
 * request riding the already-cached journal/task/report context.
 *
 * [summarize] must never throw: anything that keeps a valid [HrsChunkSummary] from coming back (no
 * summarizing client configured, a network error, a malformed response, ...) is reported as `null`.
 * The caller stores nothing in that case —
 * [HrsDelegationLog][software.medusa.flow.harness.history.HrsDelegationLog] is simply left without
 * that chunk's summary, and the leader's rendering already degrades gracefully to
 * full/small-summary contents when a summary is absent (see
 * [software.medusa.flow.harness.history.HrsLeaderHistoryRendering]) — a failed generation never
 * blocks the run, and the next close retries.
 */
fun interface HrsChunkSummarizer {
  /**
   * Summarizes [delegationRange] (the delegations that just closed into a chunk) for the leader's
   * history. [kind] only steers the request's wording — small and big closes both rely on the
   * thread's already-in-context, never-compacted journal, so a big-chunk summary is written from
   * full delegation contents, never from any small-chunk summaries already stored.
   */
  suspend fun summarize(
      delegationRange: IntRange,
      kind: HrsChunkSummaryKind,
  ): HrsChunkSummary?

  companion object {
    /** Always degrades to `null` — for assistants (real or fake) that support no summarization. */
    val unavailable: HrsChunkSummarizer = HrsChunkSummarizer { _, _ -> null }
  }
}
