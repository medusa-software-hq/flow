package software.medusa.flow.harness.history

/**
 * The pure partition of a delegation log into the leader's history tiers, given the
 * [HrsChunkConfig] and a delegation count. No model calls — this is only the close-detection and
 * window arithmetic; summary *generation* is story 07.
 *
 * Delegations are grouped into small chunks of `s`, small chunks into big chunks of `B`. Rendered
 * oldest-first, a chunk falls into exactly one tier:
 * - **big-summary chunks** — big chunks that have fully closed *before* the full window;
 * - **small-summary chunks** — closed small chunks after the last big-summary chunk and before the
 *   window;
 * - **full-window chunks** — the last two small chunks, always rendered in full (so the leader
 *   always sees ≥ `s+1` recent rounds, never just one; the in-progress chunk is one of the two).
 *
 * The three index sets are disjoint and cover `[0, numSmallChunks)`, so there is no double-render.
 */
data class HrsChunkLayout(
    val config: HrsChunkConfig,
    val delegationCount: Int,
    val numSmallChunks: Int,
    val bigSummaryChunks: List<Int>,
    val smallSummaryChunks: List<Int>,
    val fullWindowChunks: List<Int>,
) {
  /**
   * The delegation indices belonging to small chunk [smallChunkIndex] (the last chunk may be
   * short).
   */
  fun smallChunkDelegationRange(
      smallChunkIndex: Int,
  ): IntRange {
    val start = smallChunkIndex * config.smallChunkSize
    val end = minOf(start + config.smallChunkSize, delegationCount)
    return start until end
  }

  /** The small-chunk indices belonging to big chunk [bigChunkIndex]. */
  fun bigChunkSmallChunkRange(
      bigChunkIndex: Int,
  ): IntRange {
    val start = bigChunkIndex * config.bigChunkSize
    val end = minOf(start + config.bigChunkSize, numSmallChunks)
    return start until end
  }

  companion object {
    /**
     * What just closed, if anything, now that the delegation log has grown to [delegationCount]
     * entries (story 07's trigger for summary *generation*, as opposed to the tier arithmetic
     * above, which only governs *rendering*): a small chunk closes on its `s`-th accepted report; a
     * big chunk closes on its `B`-th small chunk closing. Both can close at once (the last small
     * chunk of a big chunk), hence [HrsChunkCloseEvent] carries both.
     */
    fun closeEventAt(
        delegationCount: Int,
        config: HrsChunkConfig = HrsChunkConfig.default,
    ): HrsChunkCloseEvent? {
      if (delegationCount == 0 || delegationCount % config.smallChunkSize != 0) return null

      val closedSmallChunkIndex = delegationCount / config.smallChunkSize - 1
      val closedSmallChunkCount = closedSmallChunkIndex + 1

      val closedBigChunkIndex =
          if (closedSmallChunkCount % config.bigChunkSize == 0) {
            closedSmallChunkCount / config.bigChunkSize - 1
          } else {
            null
          }

      return HrsChunkCloseEvent(
          closedSmallChunkIndex = closedSmallChunkIndex,
          closedBigChunkIndex = closedBigChunkIndex,
      )
    }

    fun of(
        delegationCount: Int,
        config: HrsChunkConfig = HrsChunkConfig.default,
    ): HrsChunkLayout {
      require(delegationCount >= 0) { "delegationCount must be non-negative" }

      val s = config.smallChunkSize
      val b = config.bigChunkSize

      val numSmallChunks = (delegationCount + s - 1) / s // ceil

      // The window is the last two small chunks. Only the very last chunk can be in progress, so
      // every chunk strictly before the window is fully closed.
      val firstWindowChunk = maxOf(0, numSmallChunks - 2)

      // Big chunks summarize only once fully behind the window (no overlap with full-rendered
      // chunks): big chunks [0, k) where k*B ≤ firstWindowChunk.
      val numSummarizedBigChunks = firstWindowChunk / b

      return HrsChunkLayout(
          config = config,
          delegationCount = delegationCount,
          numSmallChunks = numSmallChunks,
          bigSummaryChunks = (0 until numSummarizedBigChunks).toList(),
          smallSummaryChunks = (numSummarizedBigChunks * b until firstWindowChunk).toList(),
          fullWindowChunks = (firstWindowChunk until numSmallChunks).toList(),
      )
    }
  }
}

/**
 * The chunk(s) that closed when the delegation log reached a given size — see
 * [HrsChunkLayout.closeEventAt]. [closedBigChunkIndex] is non-null only when
 * [closedSmallChunkIndex] was also the last small chunk of its big chunk.
 */
data class HrsChunkCloseEvent(
    val closedSmallChunkIndex: Int,
    val closedBigChunkIndex: Int?,
)
