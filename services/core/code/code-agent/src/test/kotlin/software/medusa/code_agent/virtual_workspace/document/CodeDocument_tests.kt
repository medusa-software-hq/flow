package software.medusa.code_agent.virtual_workspace.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownInline

class CodeDocument_tests {
  companion object {
    private val helperMethodsTitle =
        CodeNode.Region.Title(text = MarkdownInline.Text("Helper methods"))

    private val helperMethodsSummary =
        CodeNode.Region.Summary(
            paragraph =
                MarkdownBlock.Paragraph(
                    inlineContent =
                        listOf(
                            MarkdownInline.Text(
                                "This region contains helper methods for the Foo class."
                            ),
                        ),
                ),
        )
  }

  @Test
  fun test_FlatDocument_parse_basic() {
    val parsedFlatDocument =
        CodeDocument.FlatDocument.parse(
            language = CodeLanguage.Kotlin,
            block =
                CodeBlock.of(
                    "import foo",
                    "import bar",
                    "",
                    "class Foo {",
                    "  //#region Helper methods",
                    "    fun helper() = 1",
                    "  //#endregion",
                    "}",
                ),
        )

    assertEquals(
        expected =
            CodeDocument.FlatDocument(
                nodes =
                    listOf(
                        CodeDocument.FlatDocument.Node.Plain(
                            block =
                                CodeBlock.of(
                                    "import foo",
                                    "import bar",
                                    "",
                                    "class Foo {",
                                ),
                        ),
                        CodeDocument.FlatDocument.Node.RegionStartMarker(
                            title = helperMethodsTitle,
                            indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                        ),
                        CodeDocument.FlatDocument.Node.Plain(
                            block = CodeBlock.of("    fun helper() = 1"),
                        ),
                        CodeDocument.FlatDocument.Node.RegionEndMarker(
                            indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                        ),
                        CodeDocument.FlatDocument.Node.Plain(
                            block = CodeBlock.of("}"),
                        ),
                    ),
            ),
        actual = parsedFlatDocument,
    )
  }

  @Test
  fun test_FlatDocument_parse_keeps_unmatched_region_end_as_lexical_token() {
    val flatDocument =
        CodeDocument.FlatDocument.parse(
            language = CodeLanguage.Kotlin,
            block = CodeBlock.of("//#endregion"),
        )

    assertEquals(
        expected =
            CodeDocument.FlatDocument(
                nodes =
                    listOf(
                        CodeDocument.FlatDocument.Node.RegionEndMarker(
                            indentationLevel = CodeBlock.IndentationLevel(spaceCount = 0),
                        ),
                    ),
            ),
        actual = flatDocument,
    )
  }

  @Test
  fun test_restore_rejects_unclosed_region() {
    val exception =
        assertFailsWith<IllegalStateException> {
          CodeDocument.restore(
              language = CodeLanguage.Kotlin,
              content =
                  TechFileContent.Code(
                      CodeBlock.of("//#region Helper methods", "fun helper() = 1")
                  ),
              memo = null,
          )
        }

    assertEquals(
        expected = "Expected region end marker at cursor 3",
        actual = exception.message,
    )
  }

  @Test
  fun test_restore_rejects_unmatched_region_end() {
    val exception =
        assertFailsWith<IllegalArgumentException> {
          CodeDocument.restore(
              language = CodeLanguage.Kotlin,
              content = TechFileContent.Code(CodeBlock.of("//#endregion")),
              memo = null,
          )
        }

    assertEquals(
        expected = "Unexpected unmatched region end marker in document",
        actual = exception.message,
    )
  }

  @Test
  fun test_restore_basic() {
    val restoredDocument =
        CodeDocument.restore(
            language = CodeLanguage.Kotlin,
            content =
                TechFileContent.Code(
                    CodeBlock.of(
                        "import foo",
                        "import bar",
                        "",
                        "class Foo {",
                        "  //#region Helper methods",
                        "    fun helper() = 1",
                        "  //#endregion",
                        "}",
                    ),
                ),
            memo =
                CodeDocument.Memo(
                    rootContainerMemo =
                        CodeNode.Container.Memo(
                            subRegionMemoByTitle =
                                mapOf(
                                    helperMethodsTitle to
                                        CodeNode.Region.Memo(
                                            summary = helperMethodsSummary,
                                            state = CodeNode.Region.State.Collapsed,
                                            innerContainerMemo =
                                                CodeNode.Container.Memo(
                                                    subRegionMemoByTitle = emptyMap(),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    assertEquals(
        expected =
            CodeDocument(
                language = CodeLanguage.Kotlin,
                rootContainer =
                    CodeNode.Container(
                        nodes =
                            listOf(
                                CodeNode.Plain(
                                    plainBlock =
                                        CodeBlock.of(
                                            "import foo",
                                            "import bar",
                                            "",
                                            "class Foo {",
                                        ),
                                ),
                                CodeNode.Region(
                                    title = helperMethodsTitle,
                                    summary = helperMethodsSummary,
                                    state = CodeNode.Region.State.Collapsed,
                                    indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                                    innerContainer =
                                        CodeNode.Container(
                                            nodes =
                                                listOf(
                                                    CodeNode.Plain(
                                                        plainBlock =
                                                            CodeBlock.of("    fun helper() = 1"),
                                                    ),
                                                ),
                                        ),
                                ),
                                CodeNode.Plain(plainBlock = CodeBlock.of("}")),
                            ),
                    ),
            ),
        actual = restoredDocument,
    )
  }

  @Test
  fun test_dumpContent_roundTrips_explicit_region_comments() {
    val document =
        CodeDocument(
            language = CodeLanguage.Kotlin,
            rootContainer =
                CodeNode.Container(
                    nodes =
                        listOf(
                            CodeNode.Plain(plainBlock = CodeBlock.of("class Foo {")),
                            CodeNode.Region(
                                title = helperMethodsTitle,
                                summary = helperMethodsSummary,
                                state = CodeNode.Region.State.Expanded,
                                indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                                innerContainer =
                                    CodeNode.Container(
                                        nodes =
                                            listOf(
                                                CodeNode.Plain(
                                                    plainBlock =
                                                        CodeBlock.of(
                                                            "    fun helper() = 1",
                                                        ),
                                                ),
                                            ),
                                    ),
                            ),
                            CodeNode.Plain(plainBlock = CodeBlock.of("}")),
                        ),
                ),
        )

    val dumpedContent =
        with(CodeDocument.DumpContentContext(language = CodeLanguage.Kotlin)) {
          document.dumpContent()
        }

    assertEquals(
        expected =
            TechFileContent.Code(
                code =
                    CodeBlock.of(
                        "class Foo {",
                        "  //#region Helper methods",
                        "    fun helper() = 1",
                        "  //#endregion",
                        "}",
                    ),
            ),
        actual = dumpedContent,
    )
  }
}
