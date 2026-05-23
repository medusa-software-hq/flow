package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.unicode.ControlChar
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.CcMdAiCodePatcher_wire_utils.encodeToCcMarkdownString
import software.medusa.flow.core_service.worker.ai_code_engineer.CcMdAiCodePatcher_wire_utils.parseAst
import software.medusa.flow.core_service.worker.ai_code_engineer.CcMdAiCodePatcher_wire_utils.parseChangeSet
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent

class CcMdAiCodePatcher_wire_utils_tests {
  @Test
  fun test_encodeToCcMarkdownString_encodesMaskedCatalogAsMarkdown() {
    val filePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("config"),
            UnixPath.Name.Literal("module.yaml"),
        )

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    filePath to
                        MaskedCodeFileContent(
                            codeFileContent =
                                CodeFileContent.of(
                                    "alpha: 1",
                                    "beta: 2",
                                ),
                            mask =
                                MaskedCodeFileContent.Mask(
                                    maskedLineRanges =
                                        setOf(
                                            LineIndexRange.of(
                                                startIndex = LineIndex.ofOneBased(2),
                                                length = 1,
                                            ),
                                        ),
                                ),
                        ),
                ),
        )

    assertEquals(
        expected =
            buildString {
              appendLine("# INPUT")
              appendLine()
              appendLine("## config/module.yaml")
              appendLine()
              appendLine(ControlChar.STX)
              appendLine("1: alpha: 1")
              appendLine("2: [MASKED LINE]")
              append(ControlChar.ETX)
              appendLine()
            },
        actual = maskedCodeCatalog.encodeToCcMarkdownString(),
    )
  }

  @Test
  fun test_parseChangeSet_decodesUpdateAndCreateMarkdownResponse() {
    val moduleFilePath = RelativeUnixPath.of(UnixPath.Name.Literal("module.yaml"))
    val notesFilePath = RelativeUnixPath.of(UnixPath.Name.Literal("notes.md"))

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    moduleFilePath to
                        MaskedCodeFileContent(
                            codeFileContent =
                                CodeFileContent.of(
                                    "alpha: 1",
                                    "beta: 2",
                                    "gamma: 3",
                                    "delta: 4",
                                ),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                    notesFilePath to
                        MaskedCodeFileContent(
                            codeFileContent = CodeFileContent.of("obsolete"),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText = buildString {
      appendLine("# PATCHSET")
      appendLine()
      appendLine("## PLAN")
      appendLine()
      appendLine("Update the yaml and rewrite the notes file.")
      appendLine()
      appendLine("## EXECUTION")
      appendLine()
      appendLine("### UPDATE module.yaml")
      appendLine()
      appendLine("#### REPLACE 2-3")
      appendLine()
      appendLine(ControlChar.STX)
      appendLine("beta: 20")
      appendLine("gamma: 30")
      append(ControlChar.ETX)
      appendLine()
      appendLine()
      appendLine("#### DELETE 4-4")
      appendLine()
      appendLine("#### INSERT BEFORE 1")
      appendLine()
      appendLine(ControlChar.STX)
      appendLine("header: true")
      append(ControlChar.ETX)
      appendLine()
      appendLine()
      appendLine("### CREATE notes.md")
      appendLine()
      appendLine(ControlChar.STX)
      appendLine("# Notes")
      appendLine("Fresh")
      append(ControlChar.ETX)
    }

    val changeSet =
        parseChangeSet(
            responseText = responseText,
            maskedCodeCatalog = maskedCodeCatalog,
        )

    assertEquals(
        expected =
            AiCodePatcher.ChangeSet(
                changeByFilePath =
                    mapOf(
                        moduleFilePath to
                            Change.Patch(
                                fragmentByOldLineIndexRange =
                                    mapOf(
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(2),
                                            endIndexExclusive = LineIndex.ofOneBased(4),
                                        ) to
                                            Change.Patch.Fragment(
                                                newCodeBlock =
                                                    CodeBlock.of(
                                                        "beta: 20",
                                                        "gamma: 30",
                                                    ),
                                            ),
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(4),
                                            endIndexExclusive = LineIndex.ofOneBased(5),
                                        ) to Change.Patch.Fragment.Empty,
                                        LineIndexRange.empty(
                                            startIndex = LineIndex.ofOneBased(1),
                                        ) to
                                            Change.Patch.Fragment(
                                                newCodeBlock = CodeBlock.of("header: true"),
                                            ),
                                    ),
                            ),
                        notesFilePath to
                            Change.Patch(
                                fragmentByOldLineIndexRange =
                                    mapOf(
                                        LineIndexRange.of(
                                            startIndex = LineIndex.First,
                                            length = 1,
                                        ) to
                                            Change.Patch.Fragment(
                                                newCodeBlock = CodeBlock.of("# Notes", "Fresh"),
                                            ),
                                    ),
                            ),
                    ),
            ),
        actual = changeSet,
    )
  }

  @Test
  fun test_parseAst_decodesMarkdownResponseIntoFlatAst() {
    val responseText = buildString {
      appendLine("# PATCHSET")
      appendLine()
      appendLine("## EXECUTION")
      appendLine()
      appendLine("### UPDATE module.yaml")
      appendLine()
      appendLine("#### INSERT BEFORE 1")
      appendLine()
      appendLine(ControlChar.STX)
      appendLine("first")
      append(ControlChar.ETX)
      appendLine()
      appendLine()
      appendLine("#### REPLACE 2-3")
      appendLine()
      appendLine(ControlChar.STX)
      appendLine("replacement")
      append(ControlChar.ETX)
      appendLine()
      appendLine()
      appendLine("#### DELETE 5-6")
    }

    val ast = parseAst(responseText)

    assertEquals(
        expected =
            CcMdAiCodePatcher_wire_utils.BodyAstNode(
                filePatches =
                    listOf(
                        CcMdAiCodePatcher_wire_utils.FilePatchAstNode.Update(
                            filePath = "module.yaml",
                            patchFragments =
                                listOf(
                                    CcMdAiCodePatcher_wire_utils.PatchFragmentAstNode.InsertBefore(
                                        laterLineNumber = 1,
                                        rawContent = "first\n",
                                    ),
                                    CcMdAiCodePatcher_wire_utils.PatchFragmentAstNode.Replace(
                                        startLineNumber = 2,
                                        endLineNumberInclusive = 3,
                                        rawContent = "replacement\n",
                                    ),
                                    CcMdAiCodePatcher_wire_utils.PatchFragmentAstNode.Delete(
                                        startLineNumber = 5,
                                        endLineNumberInclusive = 6,
                                    ),
                                ),
                        ),
                    ),
            ),
        actual = ast,
    )
  }

  @Test
  fun test_parseChangeSet_rejectsUnknownFilePath() {
    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    RelativeUnixPath.of(UnixPath.Name.Literal("known.txt")) to
                        MaskedCodeFileContent(
                            codeFileContent = CodeFileContent.of("known"),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText = buildString {
      appendLine("# PATCHSET")
      appendLine()
      appendLine("## EXECUTION")
      appendLine()
      appendLine("### UPDATE unknown.txt")
    }

    val exception =
        assertFailsWith<IllegalArgumentException> {
          parseChangeSet(
              responseText = responseText,
              maskedCodeCatalog = maskedCodeCatalog,
          )
        }

    assertEquals(
        expected = "Patch references file not present in masked code catalog: unknown.txt",
        actual = exception.message,
    )
  }

  @Test
  fun test_parseChangeSet_rejectsReplaceWithoutRawCodeBlock() {
    val filePath = RelativeUnixPath.of(UnixPath.Name.Literal("module.yaml"))

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    filePath to
                        MaskedCodeFileContent(
                            codeFileContent = CodeFileContent.of("alpha: 1"),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText =
        """
        # PATCHSET

        ## EXECUTION

        ### UPDATE module.yaml

        #### REPLACE 1-1

        ```
        alpha: 2
        ```
        """
            .trimIndent()

    val exception =
        assertFailsWith<IllegalArgumentException> {
          parseChangeSet(
              responseText = responseText,
              maskedCodeCatalog = maskedCodeCatalog,
          )
        }

    assertEquals(
        expected = "Expected EXECUTION[0].fragments[0] to contain a raw code block",
        actual = exception.message,
    )
  }
}
