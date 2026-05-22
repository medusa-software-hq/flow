package software.medusa.flow.core_service.worker.code.raw_patchset

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.tc.ControlChar

class RawPatchSet_tests {
  @Test
  fun test_encodeToString_uses_expected_wire_format() {
    val rawPatchSet =
        RawPatchSet(
            patches =
                listOf(
                    RawFilePatch(
                        filePath = AbsoluteUnixPath.parse("/repo/module.yaml").toLiteral()!!,
                        fragments =
                            listOf(
                                RawInsertBeforeFragment(
                                    laterLineIndex = CodeBlock.LineIndex.ofOneBased(2),
                                    insertedLines =
                                        listOf(
                                            CodeBlock.Line("alpha: 1"),
                                            CodeBlock.Line.Empty,
                                        ),
                                ),
                                RawInsertAfterFragment(
                                    earlierLineIndex = CodeBlock.LineIndex.ofOneBased(4),
                                    insertedLines =
                                        listOf(
                                            CodeBlock.Line("beta: 2"),
                                        ),
                                ),
                                RawReplaceFragment(
                                    replacedLineIndexRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(6),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(8),
                                        ),
                                    replacementLines =
                                        listOf(
                                            CodeBlock.Line("gamma: 3"),
                                            CodeBlock.Line("delta: 4"),
                                        ),
                                ),
                                RawDeleteFragment(
                                    deletedLineIndexRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(10),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(12),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    assertEquals(
        expected =
            buildString {
              append(ControlChar.SOH)
              append(RawPatchSetParser.patchsetKeyword)
              append(ControlChar.STX)
              append(ControlChar.SOH)
              append("/repo/module.yaml")
              append(ControlChar.STX)
              append("INSERT_BEFORE:2")
              append(ControlChar.VT)
              append("alpha: 1")
              append(ControlChar.ST)
              append(ControlChar.RS)
              append(ControlChar.ST)
              append(ControlChar.GS)
              append("INSERT_AFTER:4")
              append(ControlChar.VT)
              append("beta: 2")
              append(ControlChar.ST)
              append(ControlChar.GS)
              append("REPLACE:6-8")
              append(ControlChar.VT)
              append("gamma: 3")
              append(ControlChar.ST)
              append(ControlChar.RS)
              append("delta: 4")
              append(ControlChar.ST)
              append(ControlChar.GS)
              append("DELETE:10-12")
              append(ControlChar.ETX)
              append(ControlChar.ETX)
            },
        actual = rawPatchSet.encodeToString(),
    )
  }

  @Test
  fun test_encodeToString_roundTrips_via_parser() {
    val rawPatchSet =
        RawPatchSet(
            patches =
                listOf(
                    RawFilePatch(
                        filePath = AbsoluteUnixPath.parse("/repo/module.yaml").toLiteral()!!,
                        fragments =
                            listOf(
                                RawInsertBeforeFragment(
                                    laterLineIndex = CodeBlock.LineIndex.ofOneBased(1),
                                    insertedLines = listOf(CodeBlock.Line("new-top: true")),
                                ),
                                RawReplaceFragment(
                                    replacedLineIndexRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(3),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(4),
                                        ),
                                    replacementLines = listOf(CodeBlock.Line("mode: strict")),
                                ),
                            ),
                    ),
                    RawFilePatch(
                        filePath =
                            AbsoluteUnixPath.parse("/repo/backend/module.yaml").toLiteral()!!,
                        fragments =
                            listOf(
                                RawDeleteFragment(
                                    deletedLineIndexRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(5),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(7),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    val encoded = rawPatchSet.encodeToString()
    val reparsed = RawPatchSetParser.parse(input = encoded)

    assertEquals(
        expected = rawPatchSet,
        actual = reparsed,
    )
  }
}
