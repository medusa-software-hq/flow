package software.medusa.flow.harness.history

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatOpenedFile
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatWorktree

/**
 * The assistant's view of the branch journal — everything, uncompressed, no ceiling (the cheap,
 * cache-optimized role). The journal is a *pure rendering* of the delegation log zipped with the
 * virtual editor's version history by timestamp: segment `k` = task_k + the net file snapshots
 * stamped `t = k` (path-ordered) + report_k. Appending a delegation only grows the tail, so the
 * whole prefix is prompt-cache friendly by construction.
 */
data object HrsBranchJournalRendering {
  /**
   * The rendering policy. [Full] is the default. [LatestVersionsOnly] is the
   * designed-but-not-default "B1" fallback: task+report pairs with no per-segment snapshots, plus a
   * single latest-version-only snapshot of every open file — flip it if real transcripts show
   * old-version confusion or unacceptable growth (costs cross-thread file caching, keeps the same
   * data model).
   */
  enum class RenderingPolicy {
    Full,
    LatestVersionsOnly,
  }

  fun HrsDelegationLog.renderAssistantJournal(
      flatWorktree: VedFlatWorktree,
      policy: RenderingPolicy = RenderingPolicy.Full,
  ): MdChapter {
    val snapshotsByTimestamp = flatWorktree.openedFiles.groupBy { it.modificationTimestamp.t }

    val segments = entries.mapIndexed { k, entry ->
      val snapshots =
          when (policy) {
            RenderingPolicy.Full -> snapshotsByTimestamp[k].orEmpty()
            RenderingPolicy.LatestVersionsOnly -> emptyList()
          }
      renderSegment(index = k, entry = entry, snapshots = snapshots)
    }

    val trailer =
        when (policy) {
          RenderingPolicy.Full -> emptyList()
          RenderingPolicy.LatestVersionsOnly -> listOf(renderLatestVersions(flatWorktree))
        }

    return MdChapter.wrapper(
        title = MdInlineContent.of("Branch journal"),
        introElement =
            MdElement(
                listOf(
                    MdBlock.Paragraph.of(
                        if (entries.isEmpty()) "The journal is empty — no delegations have run yet."
                        else
                            "Every past delegation, in full: its task, the files it settled, and its report.",
                    ),
                ),
            ),
        subChapters = segments + trailer,
    )
  }

  private fun renderSegment(
      index: Int,
      entry: HrsDelegationEntry,
      snapshots: List<VedFlatOpenedFile>,
  ): MdChapter =
      MdChapter.wrapper(
          title = MdInlineContent.of("Delegation t=$index (${entry.report.outcome})"),
          introElement =
              MdElement(
                  buildList {
                    add(MdBlock.Paragraph.of("Task: ${entry.taskDefinition.markdown}"))
                    addAll(entry.report.renderFullBlocks())
                  },
              ),
          subChapters = snapshots.map { it.renderSnapshot() },
      )

  // B1 trailer: one latest-version-only snapshot per open file (the currentContent projection).
  private fun renderLatestVersions(
      flatWorktree: VedFlatWorktree,
  ): MdChapter {
    val latestByPath =
        flatWorktree.openedFiles
            .groupBy { it.path }
            .values
            .map { versions -> versions.maxBy { it.modificationTimestamp } }
            .sortedBy { it.path.toUnixAbsolutePathString() }

    return MdChapter.wrapper(
        title = MdInlineContent.of("Open files (current)"),
        subChapters = latestByPath.map { it.renderSnapshot() },
    )
  }

  private fun VedFlatOpenedFile.renderSnapshot(): MdChapter =
      MdChapter.leaf(
          title =
              MdInlineContent.of(
                  "${path.toUnixAbsolutePathString()} (t=${modificationTimestamp.t})"
              ),
          element = MdElement(listOf(MdBlock.CodeBlock(code = content.dump()))),
      )

  // Full report for the assistant — every field, including the two optional ones even when empty is
  // avoided but the required ones always render.
  private fun HrsDelegationReport.renderFullBlocks(): List<MdBlock> = buildList {
    add(MdBlock.Paragraph.of("Outcome: $outcome"))
    add(MdBlock.Paragraph.of("Narrative: $narrative"))
    add(MdBlock.Paragraph.of("Files touched: $filesTouched"))
    add(MdBlock.Paragraph.of("Buffer changes: $bufferChanges"))
    add(MdBlock.Paragraph.of("Checks: $checksSummary"))
    if (surprises.isNotBlank()) add(MdBlock.Paragraph.of("Surprises: $surprises"))
    if (leaderNotices.isNotBlank()) add(MdBlock.Paragraph.of("Leader notices: $leaderNotices"))
  }
}
