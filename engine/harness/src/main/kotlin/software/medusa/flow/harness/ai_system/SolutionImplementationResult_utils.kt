package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.resolve
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchCommand
import software.medusa.flow.harness.ai_system.MdInlineContent_utils.extractInlineCode
import software.medusa.flow.harness.ai_system.MdInlineContent_utils.extractText
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedEntityPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * Codec between a [PatchCommand] and its ad-hoc Markdown representation.
 *
 * The patch is a `# PATCH` document with one `## ` chapter per file (its path as inline code) and
 * one `### ` chapter per edit. Every edit's line numbers are **1-based and refer to the original
 * file** shown in the worktree, so edits are order-independent and must not overlap:
 * ```
 * # PATCH
 *
 * ## `/src/Main.kt`
 *
 * ### INSERT BEFORE 1
 *
 * (code block)
 *
 * ### DELETE 3-4
 *
 * ### UPDATE 11-22
 *
 * (code block)
 * ```
 *
 * `INSERT BEFORE n` inserts before original line `n` (use one past the last line to append);
 * `DELETE a-b` and `UPDATE a-b` cover the inclusive original-line range `a..b`.
 */
internal data object SolutionImplementationResult_utils {
  private const val patchHeading = "PATCH"

  private val insertBeforeRegex = Regex("""INSERT\s+BEFORE\s+(\d+)""")

  private val deleteRangeRegex = Regex("""DELETE\s+(\d+)-(\d+)""")

  private val updateRangeRegex = Regex("""UPDATE\s+(\d+)-(\d+)""")

  private data class FileEdit(
      val names: List<UfsName.Literal>,
      val filePatch: VedFilePatch,
  )

  fun PatchCommand.dump(): MdDocument =
      MdDocument(
          rootChapter =
              MdChapter.wrapper(
                  title = MdInlineContent.of(patchHeading),
                  subChapters =
                      solutionPatch.rootDirectoryPatch.dumpFileChapters(
                          directoryPath = UfsAbsolutePath.Root,
                      ),
              ),
      )

  fun PatchCommand.Companion.load(
      document: MdDocument,
  ): PatchCommand {
    val heading = document.rootChapter.title.extractText()

    require(heading == patchHeading) { "Expected a `# $patchHeading` document, got `$heading`" }

    val fileEdits =
        document.rootChapter.subChapters.map { fileChapter ->
          FileEdit(
              names = parseFilePath(fileChapter.title.extractInlineCode()),
              filePatch = VedFilePatch(txtPatch = fileChapter.subChapters.loadTxtPatch()),
          )
        }

    return PatchCommand(
        solutionPatch =
            VedWorktreePatch(rootDirectoryPatch = buildDirectoryPatch(edits = fileEdits)),
    )
  }

  private fun List<MdChapter>.loadTxtPatch(): TxtPatch =
      TxtPatch(
          fragmentByOldLineIndexRange =
              mergeAdjacentEdits(edits = map { editChapter -> editChapter.loadEdit() }),
      )

  /**
   * Merges edits whose original-line ranges touch or overlap into a single fragment. The model
   * legitimately anchors several edits at the same spot — e.g. `INSERT BEFORE 13` followed by
   * `UPDATE 13-15` — which [TxtPatch] rejects as colliding. Concatenating their content in
   * start-then-end order preserves the intended result: an insert at a range's start lands before
   * it, an insert at its end lands after.
   */
  private fun mergeAdjacentEdits(
      edits: List<Pair<TxtLineIndexRange, TxtPatch.Fragment>>,
  ): Map<TxtLineIndexRange, TxtPatch.Fragment> {
    val sortedEdits =
        edits.sortedWith(
            compareBy(
                { (range, _) -> range.startIndex.indexZeroBased },
                { (range, _) -> range.endIndexExclusive.indexZeroBased },
            ),
        )

    val clusters = mutableListOf<MutableList<Pair<TxtLineIndexRange, TxtPatch.Fragment>>>()

    for (edit in sortedEdits) {
      val currentCluster = clusters.lastOrNull()

      val clusterEndZeroBased = currentCluster?.maxOf { (range, _) ->
        range.endIndexExclusive.indexZeroBased
      }

      // A strictly-greater start means a gap, so the edit begins a new (non-colliding) cluster;
      // otherwise it touches or overlaps the current one and is merged into it.
      if (currentCluster == null || edit.first.startIndex.indexZeroBased > clusterEndZeroBased!!) {
        clusters += mutableListOf(edit)
      } else {
        currentCluster += edit
      }
    }

    return clusters.associate { cluster ->
      val range =
          TxtLineIndexRange(
              startIndex = cluster.first().first.startIndex,
              endIndexExclusive =
                  TxtLineIndex(
                      indexZeroBased =
                          cluster.maxOf { (edit, _) -> edit.endIndexExclusive.indexZeroBased },
                  ),
          )

      val mergedContent =
          TxtBlock(lines = cluster.flatMap { (_, fragment) -> fragment.newContent.lines })

      range to TxtPatch.Fragment(newContent = mergedContent)
    }
  }

  private fun MdChapter.loadEdit(): Pair<TxtLineIndexRange, TxtPatch.Fragment> {
    val heading = title.extractText()

    insertBeforeRegex.matchEntire(heading)?.let { match ->
      return TxtLineIndexRange.empty(
          startIndex = TxtLineIndex.ofOneBased(match.groupValues[1].toInt()),
      ) to TxtPatch.Fragment(newContent = element.requireCodeContent())
    }

    deleteRangeRegex.matchEntire(heading)?.let { match ->
      element.requireNoContent()

      return rangeOf(
          startOneBased = match.groupValues[1].toInt(),
          endOneBased = match.groupValues[2].toInt(),
      ) to TxtPatch.Fragment.Empty
    }

    updateRangeRegex.matchEntire(heading)?.let { match ->
      return rangeOf(
          startOneBased = match.groupValues[1].toInt(),
          endOneBased = match.groupValues[2].toInt(),
      ) to TxtPatch.Fragment(newContent = element.requireCodeContent())
    }

    error("Unrecognized patch edit: `$heading`")
  }

  private fun MdElement.requireCodeContent(): TxtBlock {
    val codeBlock =
        blocks.singleOrNull() as? MdBlock.CodeBlock
            ?: error("Expected the edit body to be a single fenced code block")

    return TxtBlock.parse(rawContent = codeBlock.code)
  }

  private fun MdElement.requireNoContent() {
    require(blocks.isEmpty()) { "A `DELETE` edit must have no body" }
  }

  private fun rangeOf(
      startOneBased: Int,
      endOneBased: Int,
  ): TxtLineIndexRange =
      TxtLineIndexRange.of(
          startIndex = TxtLineIndex.ofOneBased(startOneBased),
          length = endOneBased - startOneBased + 1,
      )

  private fun parseFilePath(
      path: String,
  ): List<UfsName.Literal> {
    val literalPath =
        UfsAbsolutePath.parse(path).toLiteral()
            ?: error("Path `$path` is not a valid literal absolute path.")

    val names = literalPath.innerPath.names

    require(names.isNotEmpty()) { "Path `$path` does not point to a file." }

    return names
  }

  private fun buildDirectoryPatch(
      edits: List<FileEdit>,
  ): VedDirectoryPatch {
    val childPatchByName: Map<UfsName.Literal, VedEntityPatch> =
        edits
            .groupBy { edit -> edit.names.first() }
            .mapValues { (_, group) ->
              val fileEdit = group.firstOrNull { edit -> edit.names.size == 1 }

              fileEdit?.filePatch
                  ?: buildDirectoryPatch(
                      edits = group.map { edit -> edit.copy(names = edit.names.drop(1)) }
                  )
            }

    return VedDirectoryPatch(childPatchByName = childPatchByName)
  }

  private fun VedDirectoryPatch.dumpFileChapters(
      directoryPath: UfsLiteralAbsolutePath,
  ): List<MdChapter> =
      childPatchByName.entries
          .sortedBy { (name, _) -> name.content }
          .flatMap { (name, childPatch) ->
            val childPath = directoryPath.resolve(name = name)

            when (childPatch) {
              is VedFilePatch -> listOf(childPatch.dumpFileChapter(filePath = childPath))
              is VedDirectoryPatch -> childPatch.dumpFileChapters(directoryPath = childPath)
            }
          }

  private fun VedFilePatch.dumpFileChapter(
      filePath: UfsLiteralAbsolutePath,
  ): MdChapter =
      MdChapter.wrapper(
          title =
              MdInlineContent(
                  inlineNodes = listOf(MdInlineNode.Code(filePath.toUnixAbsolutePathString())),
              ),
          subChapters =
              txtPatch.fragmentByOldLineIndexRange.entries
                  .sortedBy { (range, _) -> range.startIndex.indexZeroBased }
                  .map { (range, fragment) -> dumpEditChapter(range = range, fragment = fragment) },
      )

  private fun dumpEditChapter(
      range: TxtLineIndexRange,
      fragment: TxtPatch.Fragment,
  ): MdChapter {
    val startOneBased = range.startIndex.indexOneBased
    val endOneBased = range.endIndexExclusive.indexZeroBased

    return when {
      range.startIndex == range.endIndexExclusive ->
          MdChapter.leaf(
              title = MdInlineContent.of("INSERT BEFORE $startOneBased"),
              element = fragment.newContent.dumpCodeElement(),
          )

      fragment.newContent.lines.isEmpty() ->
          MdChapter.leaf(
              title = MdInlineContent.of("DELETE $startOneBased-$endOneBased"),
              element = MdElement.Empty,
          )

      else ->
          MdChapter.leaf(
              title = MdInlineContent.of("UPDATE $startOneBased-$endOneBased"),
              element = fragment.newContent.dumpCodeElement(),
          )
    }
  }

  private fun TxtBlock.dumpCodeElement(): MdElement =
      MdElement(blocks = listOf(MdBlock.CodeBlock(code = dump())))
}
