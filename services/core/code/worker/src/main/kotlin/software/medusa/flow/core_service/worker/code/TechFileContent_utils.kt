package software.medusa.flow.core_service.worker.code

import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change.Patch
import software.medusa.flow.core_service.worker.utils.withNextOrNull

fun TechFileContent.Code.applyChange(
    patch: Patch,
): TechFileContent.Code {
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
      val followupEndIndexExclusive = nextPatchStartIndex ?: code.height

      addAll(
          oldLines.subList(followupStartIndex, followupEndIndexExclusive),
      )
    }
  }

  return TechFileContent.Code(
      code = CodeBlock(lines = newLines),
  )
}
