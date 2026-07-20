package software.medusa.flow.harness.history

/**
 * The only harness-side history *store*: the append-only sequence of closed delegations, plus the
 * chunk summaries recorded at chunk close. Everything else the leader and assistant read (the
 * journal, the chunked leader view) is a *pure rendering* of this plus the virtual editor's version
 * history — nothing captures file content here.
 *
 * A delegation's index in [entries] is its timestamp: entry `k` is delegation `t = k`, the same
 * axis the virtual editor stamps its versions on, so the two zip by index at render time.
 *
 * [smallChunkSummaries] / [bigChunkSummaries] are keyed by chunk index; a missing key means "not
 * summarized yet" and the renderer degrades gracefully (full contents / small summaries).
 */
data class HrsDelegationLog(
    val entries: List<HrsDelegationEntry> = emptyList(),
    val smallChunkSummaries: Map<Int, HrsChunkSummary> = emptyMap(),
    val bigChunkSummaries: Map<Int, HrsChunkSummary> = emptyMap(),
) {
  val size: Int
    get() = entries.size

  /** Appends a closed delegation. Segment close = append the report; nothing is captured. */
  fun append(
      entry: HrsDelegationEntry,
  ): HrsDelegationLog = copy(entries = entries + entry)

  /** Records the summary produced when small chunk [chunkIndex] closed. */
  fun withSmallChunkSummary(
      chunkIndex: Int,
      summary: HrsChunkSummary,
  ): HrsDelegationLog = copy(smallChunkSummaries = smallChunkSummaries + (chunkIndex to summary))

  /** Records the summary produced when big chunk [chunkIndex] closed. */
  fun withBigChunkSummary(
      chunkIndex: Int,
      summary: HrsChunkSummary,
  ): HrsDelegationLog = copy(bigChunkSummaries = bigChunkSummaries + (chunkIndex to summary))
}
