package software.medusa.flow.core_service.worker.code.raw_patchset

import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code.raw_patchset.CharStream.CharClass
import software.medusa.flow.core_service.worker.code.tc.ControlChar

class RawParserException(message: String) : RuntimeException(message)

data object RawPatchSetParser : Parser<RawPatchSet> {
  val ebnfGrammarDescription =
      """
    (* a set of patches for files *)
    patchset = "${ControlChar.SOH}", "$patchsetKeyword", "${ControlChar.STX}", file_patch, { "${ControlChar.FS}", file_patch }, "${ControlChar.ETX}" ;

    (* patch for file at [file_path] consisting of non-overlapping [patch_fragment]s, line-sorted *)
    file_patch = "${ControlChar.SOH}", file_path, "${ControlChar.STX}", patch_fragment, { "${ControlChar.GS}", patch_fragment }, "${ControlChar.ETX}" ;

    (* patch fragment referring to pre-patch line numbers *)
    patch_fragment = insert_before | insert_after | replace | delete ;

    (* insert [lines] before line [line_number] *)
    insert_before = "${RawInsertBeforeFragmentParser.keyword}:", line_number, "${ControlChar.VT}", lines ;

    (* insert [lines] after line [line_number] *)
    insert_after = "${RawInsertAfterFragmentParser.keyword}:", line_number, "${ControlChar.VT}", lines ;

    (* replace lines in [line_range] with [lines] *)
    replace = "${RawReplaceFragmentParser.keyword}:", line_range, "${ControlChar.VT}", lines ;

    (* delete lines in [line_range] *)
    delete = "${RawDeleteFragmentParser.keyword}:", line_range ;

    (* line range, inclusive on both ends *)
    line_range = line_number, "${SpecificRawPatchFragmentParser.rangeSeparatorChar}", line_number ;

    (* 1-based line number *)
    line_number = nonzero_digit, { digit } ;

    digit = "0" | nonzero_digit ;
    nonzero_digit = "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;

    (* absolute Unix-style file path *)
    file_path = text ;

    (* one or more consecutive lines *)
    lines = line, { "${ControlChar.RS}", line } ;

    (* a single possibly empty text line, terminated by ST *)
    line = text, "${ControlChar.ST}" ;

    (* text string, possibly empty *)
    text = { text_char } ;
    text_char = ? any non-control character ? ;
  """
          .trimIndent()

  const val patchsetKeyword = "PATCHSET"

  context(charStream: CharStream)
  override fun parse(): RawPatchSet {
    charStream.consume(ControlChar.SOH) {
      throw RawParserException(
          "Expected patch set to start with SOH control character at offset ${charStream.currentOffset}"
      )
    }

    charStream.consume(patchsetKeyword) {
      throw RawParserException(
          "Expected patch set keyword '$patchsetKeyword' at offset ${charStream.currentOffset}"
      )
    }

    charStream.consume(ControlChar.STX) {
      throw RawParserException(
          "Expected patch set keyword '$patchsetKeyword' to be followed by STX control character at offset ${charStream.currentOffset}"
      )
    }

    val patches = RawFilePatchParser.parseSequence(ControlChar.FS).toList()

    charStream.consume(ControlChar.ETX) {
      throw RawParserException(
          "Expected patch set to end with ETX control character at offset ${charStream.currentOffset}"
      )
    }

    return RawPatchSet(
        patches = patches,
    )
  }
}

data object RawFilePatchParser : Parser<RawFilePatch> {
  context(charStream: CharStream)
  override fun parse(): RawFilePatch {
    charStream.consume(ControlChar.SOH) {
      throw RawParserException(
          "Expected file patch to start with SOH control character at offset ${charStream.currentOffset}"
      )
    }

    val filePath = parsePath()

    charStream.consume(ControlChar.STX) {
      throw RawParserException(
          "Expected file path in file patch to be followed by STX control character at offset ${charStream.currentOffset}"
      )
    }

    val fragments = RawPatchFragmentParser.parseSequence(ControlChar.GS).toList()

    charStream.consume(ControlChar.ETX) {
      throw RawParserException(
          "Expected file patch to end with ETX control character at offset ${charStream.currentOffset}"
      )
    }

    return RawFilePatch(
        filePath = filePath,
        fragments = fragments,
    )
  }

  context(charStream: CharStream)
  private fun parsePath(): LiteralAbsoluteUnixPath {
    val originalOffset = charStream.currentOffset

    val pathString = charStream.extract(CharClass.NonControl)
    val path = AbsoluteUnixPath.parse(pathString)

    val literalPath =
        path.toLiteral()
            ?: throw RawParserException(
                "Expected file path to be a literal absolute Unix path at offset $originalOffset"
            )

    return literalPath
  }
}

data object RawPatchFragmentParser : Parser<RawPatchFragment> {
  context(charStream: CharStream)
  override fun parse(): RawPatchFragment {
    val relevantParser =
        when {
          RawInsertBeforeFragmentParser.isRelevant() -> RawInsertBeforeFragmentParser
          RawInsertAfterFragmentParser.isRelevant() -> RawInsertAfterFragmentParser
          RawReplaceFragmentParser.isRelevant() -> RawReplaceFragmentParser
          RawDeleteFragmentParser.isRelevant() -> RawDeleteFragmentParser
          else ->
              throw RawParserException(
                  "Unrecognized patch fragment type at offset ${charStream.currentOffset}"
              )
        }

    return relevantParser.parse()
  }
}

sealed class SpecificRawPatchFragmentParser<FragmentT : RawPatchFragment> :
    Parser<RawPatchFragment> {

  companion object {
    const val prefixDelimiterChar = ':'
    const val rangeSeparatorChar = '-'

    private val lineParser =
        object : Parser<CodeBlock.Line> {
          context(charStream: CharStream)
          override fun parse(): CodeBlock.Line {
            val lineContent = charStream.extract(CharClass.NonControl)

            charStream.consume(ControlChar.ST) {
              throw RawParserException(
                  "Expected introduced line to end with ST control character at offset ${charStream.currentOffset}"
              )
            }

            return CodeBlock.Line(lineContent)
          }
        }
  }

  context(charStream: CharStream)
  final override fun parse(): RawPatchFragment {
    charStream.consume(keyword) {
      throw RawParserException(
          "Expected patch fragment type prefix '$keyword' at offset ${charStream.currentOffset}"
      )
    }

    charStream.consume(prefixDelimiterChar) {
      throw RawParserException(
          "Expected patch fragment type prefix '$keyword' to be followed by delimiter '$prefixDelimiterChar' at offset ${charStream.currentOffset}"
      )
    }

    return parseContent()
  }

  context(charStream: CharStream)
  fun isRelevant(): Boolean = charStream.startsWith(keyword)

  context(charStream: CharStream)
  protected fun parseLineIndexRange(): CodeBlock.LineIndexRange {
    val startLineIndex = parseLineIndex()

    charStream.consume(rangeSeparatorChar) {
      throw RawParserException(
          "Expected line index range to contain separator '$rangeSeparatorChar' at offset ${charStream.currentOffset}"
      )
    }

    val endLineIndex = parseLineIndex()

    return CodeBlock.LineIndexRange(
        startIndex = startLineIndex,
        endIndexExclusive = endLineIndex,
    )
  }

  context(charStream: CharStream)
  protected fun parseLineIndex(): LineIndex {
    val originalOffset = charStream.currentOffset

    val lineNumberString = charStream.extract(CharClass.Digit)

    val lineNumber =
        lineNumberString.toIntOrNull()
            ?: throw RawParserException(
                "Expected line number to be a valid integer at offset $originalOffset"
            )

    return LineIndex.ofOneBased(lineNumber)
  }

  context(charStream: CharStream)
  protected fun parseIntroducedLines(): List<CodeBlock.Line> {
    charStream.consume(ControlChar.VT) {
      throw RawParserException(
          "Expected introduced lines to be preceded by VT control character at offset ${charStream.currentOffset}"
      )
    }

    return lineParser.parseSequence(ControlChar.RS).toList()
  }

  abstract val keyword: String

  context(charStream: CharStream)
  abstract fun parseContent(): FragmentT
}

data object RawInsertBeforeFragmentParser :
    SpecificRawPatchFragmentParser<RawInsertBeforeFragment>() {
  override val keyword = "INSERT_BEFORE"

  context(charStream: CharStream)
  override fun parseContent(): RawInsertBeforeFragment {
    val laterLineIndex = parseLineIndex()

    val insertedLines = parseIntroducedLines()

    return RawInsertBeforeFragment(
        laterLineIndex = laterLineIndex,
        insertedLines = insertedLines,
    )
  }
}

data object RawInsertAfterFragmentParser :
    SpecificRawPatchFragmentParser<RawInsertAfterFragment>() {
  override val keyword = "INSERT_AFTER"

  context(charStream: CharStream)
  override fun parseContent(): RawInsertAfterFragment {
    val earlierLineIndex = parseLineIndex()

    val insertedLines = parseIntroducedLines()

    return RawInsertAfterFragment(
        earlierLineIndex = earlierLineIndex,
        insertedLines = insertedLines,
    )
  }
}

data object RawReplaceFragmentParser : SpecificRawPatchFragmentParser<RawReplaceFragment>() {
  override val keyword = "REPLACE"

  context(charStream: CharStream)
  override fun parseContent(): RawReplaceFragment {
    val replacedLineIndexRange = parseLineIndexRange()

    val replacementLines = parseIntroducedLines()

    return RawReplaceFragment(
        replacedLineIndexRange = replacedLineIndexRange,
        replacementLines = replacementLines,
    )
  }
}

data object RawDeleteFragmentParser : SpecificRawPatchFragmentParser<RawDeleteFragment>() {
  override val keyword = "DELETE"

  context(charStream: CharStream)
  override fun parseContent(): RawDeleteFragment {
    val deletedLineIndexRange = parseLineIndexRange()

    return RawDeleteFragment(
        deletedLineIndexRange = deletedLineIndexRange,
    )
  }
}
