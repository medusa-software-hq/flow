package software.medusa.flow.core_service.worker.code.raw_patchset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.tc.ControlChar

class RawPatchSetParser_tests {
  @Test
  fun test_parse_patchSet_withAllFragmentTypes() {
    val rawPatchSet = buildString {
      append(ControlChar.SOH)
      append(RawPatchSetParser.patchsetKeyword)
      append(ControlChar.STX)

      append(ControlChar.SOH)
      append("/tmp/example.txt")
      append(ControlChar.STX)

      append("INSERT_BEFORE:2")
      append(ControlChar.VT)
      append("prepended line")
      append(ControlChar.ST)
      append(ControlChar.RS)
      append("")
      append(ControlChar.ST)

      append(ControlChar.GS)
      append("INSERT_AFTER:4")
      append(ControlChar.VT)
      append("appended line")
      append(ControlChar.ST)

      append(ControlChar.GS)
      append("REPLACE:6-8")
      append(ControlChar.VT)
      append("replacement A")
      append(ControlChar.ST)
      append(ControlChar.RS)
      append("replacement B")
      append(ControlChar.ST)

      append(ControlChar.GS)
      append("DELETE:10-12")

      append(ControlChar.ETX)
      append(ControlChar.ETX)
    }

    val parsed = with(CharStream(rawPatchSet)) { RawPatchSetParser.parse() }

    assertEquals(
        expected =
            RawPatchSet(
                patches =
                    listOf(
                        RawFilePatch(
                            filePath = AbsoluteUnixPath.parse("/tmp/example.txt").toLiteral()!!,
                            fragments =
                                listOf(
                                    RawInsertBeforeFragment(
                                        laterLineIndex = CodeBlock.LineIndex.ofOneBased(2),
                                        insertedLines =
                                            listOf(
                                                CodeBlock.Line("prepended line"),
                                                CodeBlock.Line.Empty,
                                            ),
                                    ),
                                    RawInsertAfterFragment(
                                        earlierLineIndex = CodeBlock.LineIndex.ofOneBased(4),
                                        insertedLines =
                                            listOf(
                                                CodeBlock.Line("appended line"),
                                            ),
                                    ),
                                    RawReplaceFragment(
                                        replacedLineIndexRange =
                                            CodeBlock.LineIndexRange(
                                                startIndex = CodeBlock.LineIndex.ofOneBased(6),
                                                endIndexExclusive =
                                                    CodeBlock.LineIndex.ofOneBased(8),
                                            ),
                                        replacementLines =
                                            listOf(
                                                CodeBlock.Line("replacement A"),
                                                CodeBlock.Line("replacement B"),
                                            ),
                                    ),
                                    RawDeleteFragment(
                                        deletedLineIndexRange =
                                            CodeBlock.LineIndexRange(
                                                startIndex = CodeBlock.LineIndex.ofOneBased(10),
                                                endIndexExclusive =
                                                    CodeBlock.LineIndex.ofOneBased(12),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ),
        actual = parsed,
    )
  }

  @Test
  fun test_parse_patchSet_invalidFilePath_throws() {
    val rawPatchSet = buildString {
      append(ControlChar.SOH)
      append(RawPatchSetParser.patchsetKeyword)
      append(ControlChar.STX)
      append(ControlChar.SOH)
      append("relative/file.txt")
      append(ControlChar.STX)
      append("DELETE:1-2")
      append(ControlChar.ETX)
      append(ControlChar.ETX)
    }

    val exception =
        assertFailsWith<IllegalArgumentException> {
          with(CharStream(rawPatchSet)) { RawPatchSetParser.parse() }
        }

    assertEquals(
        expected = "Absolute path string must start with '/' character",
        actual = exception.message,
    )
  }

  @Test
  fun test_parse_patchSet_missingLineTerminatorInIntroducedLine_throws() {
    val rawPatchSet = buildString {
      append(ControlChar.SOH)
      append(RawPatchSetParser.patchsetKeyword)
      append(ControlChar.STX)
      append(ControlChar.SOH)
      append("/tmp/example.txt")
      append(ControlChar.STX)
      append("INSERT_BEFORE:1")
      append(ControlChar.VT)
      append("unterminated line")
      append(ControlChar.ETX)
      append(ControlChar.ETX)
    }

    val exception =
        assertFailsWith<RawParserException> {
          with(CharStream(rawPatchSet)) { RawPatchSetParser.parse() }
        }

    assertTrue(
        actual =
            exception.message!!.startsWith(
                "Expected introduced line to end with ST control character at offset "
            ),
    )
  }
}
