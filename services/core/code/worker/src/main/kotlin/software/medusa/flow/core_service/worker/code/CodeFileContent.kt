package software.medusa.flow.core_service.worker.code

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.Patch
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.utils.withNextOrNull

/** Content of a code file. */
@JvmInline
value class CodeFileContent(
    /** A single block containing the whole file content. */
    val code: CodeBlock,
) {
  companion object {
    /** A code file with no content (i.e. an empty file). */
    val Empty = CodeFileContent(code = CodeBlock.Empty)

    fun of(
        vararg lines: String,
    ): CodeFileContent = of(lines = lines.toList())

    fun of(
        lines: List<String>,
    ): CodeFileContent =
        CodeFileContent(
            code = CodeBlock.of(lines = lines),
        )

    fun parse(
        rawContent: String,
    ): CodeFileContent =
        CodeFileContent(
            code = CodeBlock.parse(rawContent = rawContent),
        )
  }

  val indexedLines: Sequence<CodeBlock.IndexedLine>
    get() = code.buildIndexedLines(baseIndex = CodeBlock.LineIndex.First)

  /** Dumps the content of the code file as a string with LF-terminated lines. */
  fun dump(): String = code.dump()

  fun applyPatch(
      patch: Patch,
  ): CodeFileContent {
    val oldLines = code.lines

    val patchEntries: List<Map.Entry<LineIndexRange, Patch.Fragment>> =
        patch.fragmentByOldLineIndexRange.entries.sortedBy { (lineIndexRange, _) ->
          lineIndexRange.startIndex
        }

    val (firstPatchIndexRange, _) = patchEntries.firstOrNull() ?: return this

    val newLines = buildList {
      val firstPatchStartIndex = firstPatchIndexRange.startIndex.indexZeroBased

      addAll(
          oldLines.subList(0, firstPatchStartIndex),
      )

      for ((patchEntry, nextPatchEntry) in patchEntries.withNextOrNull()) {
        val (patchIndexRange, patchFragment) = patchEntry
        val patchCodeBlock = patchFragment.newCodeBlock

        addAll(patchCodeBlock.lines)

        val followupStartIndex = patchIndexRange.endIndexExclusive.indexZeroBased
        val nextPatchStartIndex = nextPatchEntry?.key?.startIndex?.indexZeroBased
        val followupEndIndexExclusive = nextPatchStartIndex ?: code.lineCount

        addAll(
            oldLines.subList(followupStartIndex, followupEndIndexExclusive),
        )
      }
    }

    return CodeFileContent(
        code = CodeBlock(lines = newLines),
    )
  }
}
