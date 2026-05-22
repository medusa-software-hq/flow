package software.medusa.flow.core_service.worker.code.raw_patchset

import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.tc.ControlChar

data class RawPatchSet(
    val patches: List<RawFilePatch>,
) {
  fun encodeToString(): String = buildString {
    append(ControlChar.SOH)
    append(RawPatchSetParser.patchsetKeyword)
    append(ControlChar.STX)
    append(
        patches.joinToString(separator = ControlChar.FS.toString()) { patch ->
          patch.encodeToString()
        }
    )
    append(ControlChar.ETX)
  }
}

data class RawFilePatch(
    val filePath: LiteralAbsoluteUnixPath,
    val fragments: List<RawPatchFragment>,
) {
  fun encodeToString(): String = buildString {
    append(ControlChar.SOH)
    append(filePath.toUnixAbsolutePathString())
    append(ControlChar.STX)
    append(
        fragments.joinToString(separator = ControlChar.GS.toString()) { fragment ->
          fragment.encodeToString()
        }
    )
    append(ControlChar.ETX)
  }
}

sealed class RawPatchFragment {
  abstract fun encodeToString(): String
}

data class RawInsertBeforeFragment(
    val laterLineIndex: CodeBlock.LineIndex,
    val insertedLines: List<CodeBlock.Line>,
) : RawPatchFragment() {
  override fun encodeToString(): String = buildString {
    append(RawInsertBeforeFragmentParser.keyword)
    append(SpecificRawPatchFragmentParser.prefixDelimiterChar)
    append(laterLineIndex.indexOneBased)
    append(ControlChar.VT)
    append(insertedLines.encodeToRawString())
  }
}

data class RawInsertAfterFragment(
    val earlierLineIndex: CodeBlock.LineIndex,
    val insertedLines: List<CodeBlock.Line>,
) : RawPatchFragment() {
  override fun encodeToString(): String = buildString {
    append(RawInsertAfterFragmentParser.keyword)
    append(SpecificRawPatchFragmentParser.prefixDelimiterChar)
    append(earlierLineIndex.indexOneBased)
    append(ControlChar.VT)
    append(insertedLines.encodeToRawString())
  }
}

data class RawReplaceFragment(
    val replacedLineIndexRange: CodeBlock.LineIndexRange,
    val replacementLines: List<CodeBlock.Line>,
) : RawPatchFragment() {
  override fun encodeToString(): String = buildString {
    append(RawReplaceFragmentParser.keyword)
    append(SpecificRawPatchFragmentParser.prefixDelimiterChar)
    append(replacedLineIndexRange.startIndex.indexOneBased)
    append(SpecificRawPatchFragmentParser.rangeSeparatorChar)
    append(replacedLineIndexRange.endIndexExclusive.indexOneBased)
    append(ControlChar.VT)
    append(replacementLines.encodeToRawString())
  }
}

data class RawDeleteFragment(
    val deletedLineIndexRange: CodeBlock.LineIndexRange,
) : RawPatchFragment() {
  override fun encodeToString(): String = buildString {
    append(RawDeleteFragmentParser.keyword)
    append(SpecificRawPatchFragmentParser.prefixDelimiterChar)
    append(deletedLineIndexRange.startIndex.indexOneBased)
    append(SpecificRawPatchFragmentParser.rangeSeparatorChar)
    append(deletedLineIndexRange.endIndexExclusive.indexOneBased)
  }
}

private fun List<CodeBlock.Line>.encodeToRawString(): String =
    joinToString(separator = ControlChar.RS.toString()) { line -> line.content + ControlChar.ST }
