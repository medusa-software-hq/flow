package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.serialization.ccon.CconElement
import software.medusa.commons.serialization.ccon.CconRecord
import software.medusa.commons.serialization.ccon.CconString
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.CconAiCodePatcher_wire_utils.encodeToCconString
import software.medusa.flow.core_service.worker.ai_code_engineer.CconAiCodePatcher_wire_utils.parseAst
import software.medusa.flow.core_service.worker.ai_code_engineer.CconAiCodePatcher_wire_utils.parseChangeSet
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.filesystem.tech.TechFileContent

class CconAiCodePatcher_wire_utils_tests {
  @Test
  fun test_encodeToCconString_encodesMaskedCatalogAsStructuredCcon() {
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
                                TechFileContent.Code.of(
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

    val parsed = CconElement.decodeFromString(maskedCodeCatalog.encodeToCconString())

    assertEquals(
        expected =
            CconRecord(
                tagName = "patches",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "input_file",
                            headerValues = listOf("config/module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "literal_line",
                                        headerValues = listOf("1", "alpha: 1"),
                                        childElements = emptyList(),
                                    ),
                                    CconRecord(
                                        tagName = "masked_line",
                                        headerValues = listOf("Masked line"),
                                        childElements = emptyList(),
                                    ),
                                ),
                        ),
                    ),
            ),
        actual = parsed,
    )
  }

  @Test
  fun test_parseChangeSet_decodesPatchResponse() {
    val filePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("module.yaml"),
        )

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    filePath to
                        MaskedCodeFileContent(
                            codeFileContent =
                                TechFileContent.Code.of(
                                    "alpha: 1",
                                    "beta: 2",
                                    "gamma: 3",
                                ),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText =
        CconRecord(
                tagName = "patches",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "patch",
                            headerValues = listOf("module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "replace",
                                        headerValues = listOf("2", "3"),
                                        childElements =
                                            listOf(
                                                CconString("beta: 20"),
                                                CconString("delta: 4"),
                                            ),
                                    ),
                                    CconRecord(
                                        tagName = "delete",
                                        headerValues = listOf("4", "4"),
                                        childElements = emptyList(),
                                    ),
                                ),
                        ),
                    ),
            )
            .encodeToString()

    val patchSet =
        parseChangeSet(
            responseText = responseText,
            maskedCodeCatalog = maskedCodeCatalog,
        )

    assertEquals(
        expected =
            AiCodePatcher.ChangeSet(
                changeByFilePath =
                    mapOf(
                        filePath to
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
                                                        "delta: 4",
                                                    ),
                                            ),
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(4),
                                            endIndexExclusive = LineIndex.ofOneBased(5),
                                        ) to Change.Patch.Fragment.Empty,
                                    ),
                            ),
                    ),
            ),
        actual = patchSet,
    )
  }

  @Test
  fun test_parseAst_decodesPatchResponseIntoFlatAst() {
    val responseText =
        CconRecord(
                tagName = "patches",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "patch",
                            headerValues = listOf("module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "insert_before",
                                        headerValues = listOf("1"),
                                        childElements = listOf(CconString("first")),
                                    ),
                                    CconRecord(
                                        tagName = "replace",
                                        headerValues = listOf("2", "3"),
                                        childElements = listOf(CconString("replacement")),
                                    ),
                                    CconRecord(
                                        tagName = "delete",
                                        headerValues = listOf("5", "6"),
                                        childElements = emptyList(),
                                    ),
                                ),
                        ),
                    ),
            )
            .encodeToString()

    val ast = parseAst(responseText)

    assertEquals(
        expected =
            CconAiCodePatcher_wire_utils.BodyAstNode(
                filePatches =
                    listOf(
                        CconAiCodePatcher_wire_utils.FilePatchAstNode(
                            filePath = "module.yaml",
                            patchFragments =
                                listOf(
                                    CconAiCodePatcher_wire_utils.PatchFragmentAstNode.InsertBefore(
                                        laterLineNumber = 1,
                                        lines = listOf("first"),
                                    ),
                                    CconAiCodePatcher_wire_utils.PatchFragmentAstNode.Replace(
                                        startLineNumber = 2,
                                        endLineNumberInclusive = 3,
                                        lines = listOf("replacement"),
                                    ),
                                    CconAiCodePatcher_wire_utils.PatchFragmentAstNode.Delete(
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
                            codeFileContent = TechFileContent.Code.of("known"),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText =
        CconRecord(
                tagName = "patches",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "patch",
                            headerValues = listOf("unknown.txt"),
                            childElements = emptyList(),
                        ),
                    ),
            )
            .encodeToString()

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
  fun test_parseChangeSet_rejectsReplaceWithoutLines() {
    val filePath = RelativeUnixPath.of(UnixPath.Name.Literal("module.yaml"))

    val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    filePath to
                        MaskedCodeFileContent(
                            codeFileContent = TechFileContent.Code.of("alpha: 1"),
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )

    val responseText =
        CconRecord(
                tagName = "patches",
                headerValues = emptyList(),
                childElements =
                    listOf(
                        CconRecord(
                            tagName = "patch",
                            headerValues = listOf("module.yaml"),
                            childElements =
                                listOf(
                                    CconRecord(
                                        tagName = "replace",
                                        headerValues = listOf("1", "1"),
                                        childElements = emptyList(),
                                    ),
                                ),
                        ),
                    ),
            )
            .encodeToString()

    val exception =
        assertFailsWith<IllegalArgumentException> {
          parseChangeSet(
              responseText = responseText,
              maskedCodeCatalog = maskedCodeCatalog,
          )
        }

    assertEquals(
        expected = "Expected patch[0].fragments[0] to contain at least one replacement line",
        actual = exception.message,
    )
  }
}
