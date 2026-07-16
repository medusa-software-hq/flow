package software.medusa.flow.harness.history

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent

/**
 * The leader's chunk-summarized history rendering: big-chunk summaries, then small-chunk summaries,
 * then the last two small chunks in full — oldest first, **no file snapshots** (file content
 * reaches the leader only through the exposed buffer). A missing summary degrades gracefully: a
 * small chunk renders its delegations in full; a big chunk renders its small chunks (each summary
 * or, failing that, full). Correctness never depends on compression.
 */
data object HrsLeaderHistoryRendering {
  fun HrsDelegationLog.renderLeaderHistory(
      config: HrsChunkConfig = HrsChunkConfig.default,
  ): MdChapter {
    val layout = HrsChunkLayout.of(delegationCount = size, config = config)

    val units = buildList {
      layout.bigSummaryChunks.forEach { g ->
        add(renderBigChunk(bigChunkIndex = g, layout = layout))
      }
      layout.smallSummaryChunks.forEach { c ->
        addAll(renderSmallChunk(smallChunkIndex = c, layout = layout))
      }
      layout.fullWindowChunks.forEach { c ->
        addAll(renderDelegationsFull(smallChunkIndex = c, layout = layout))
      }
    }

    return MdChapter.wrapper(
        title = MdInlineContent.of("History"),
        introElement =
            MdElement(
                listOf(
                    MdBlock.Paragraph.of(
                        if (size == 0) "No delegations have run yet."
                        else
                            "Past delegations, oldest first: older ones summarized, the most recent shown in full.",
                    ),
                ),
            ),
        subChapters = units,
    )
  }

  // A big chunk: its stored summary, or (degraded) each of its small chunks rendered in turn.
  private fun HrsDelegationLog.renderBigChunk(
      bigChunkIndex: Int,
      layout: HrsChunkLayout,
  ): MdChapter {
    val smallChunkRange = layout.bigChunkSmallChunkRange(bigChunkIndex)

    val summary = bigChunkSummaries[bigChunkIndex]
    if (summary != null) {
      val delegationRange =
          layout.smallChunkDelegationRange(smallChunkRange.first).first..layout
                  .smallChunkDelegationRange(smallChunkRange.last)
                  .last
      return summaryChapter(
          label = "Delegations t=$delegationRange (big-chunk summary)",
          summary = summary,
      )
    }

    return MdChapter.wrapper(
        title = MdInlineContent.of("Big chunk of delegations (summary pending)"),
        subChapters =
            smallChunkRange.flatMap { c -> renderSmallChunk(smallChunkIndex = c, layout = layout) },
    )
  }

  // A small chunk: its stored summary (one chapter), or (degraded) its delegations rendered in
  // full.
  private fun HrsDelegationLog.renderSmallChunk(
      smallChunkIndex: Int,
      layout: HrsChunkLayout,
  ): List<MdChapter> {
    val summary = smallChunkSummaries[smallChunkIndex]
    if (summary != null) {
      val range = layout.smallChunkDelegationRange(smallChunkIndex)
      return listOf(summaryChapter(label = "Delegations t=$range (summary)", summary = summary))
    }
    return renderDelegationsFull(smallChunkIndex = smallChunkIndex, layout = layout)
  }

  private fun HrsDelegationLog.renderDelegationsFull(
      smallChunkIndex: Int,
      layout: HrsChunkLayout,
  ): List<MdChapter> =
      layout.smallChunkDelegationRange(smallChunkIndex).map { k ->
        val entry = entries[k]
        MdChapter.leaf(
            title = MdInlineContent.of("Delegation t=$k (${entry.report.outcome})"),
            element =
                MdElement(
                    buildList {
                      add(MdBlock.Paragraph.of("Task: ${entry.taskDefinition.markdown}"))
                      addAll(entry.report.renderLeaderBlocks())
                    },
                ),
        )
      }

  private fun summaryChapter(
      label: String,
      summary: HrsChunkSummary,
  ): MdChapter =
      MdChapter.leaf(
          title = MdInlineContent.of(label),
          element = MdElement(listOf(MdBlock.Paragraph.of(summary.markdown))),
      )

  // Report fields for the leader — no snapshots; optional fields omitted when empty.
  internal fun HrsDelegationReport.renderLeaderBlocks(): List<MdBlock> = buildList {
    add(MdBlock.Paragraph.of("Narrative: $narrative"))
    add(MdBlock.Paragraph.of("Files touched: $filesTouched"))
    add(MdBlock.Paragraph.of("Buffer changes: $bufferChanges"))
    add(MdBlock.Paragraph.of("Checks: $checksSummary"))
    if (surprises.isNotBlank()) add(MdBlock.Paragraph.of("Surprises: $surprises"))
    if (leaderNotices.isNotBlank()) add(MdBlock.Paragraph.of("Leader notices: $leaderNotices"))
  }
}
