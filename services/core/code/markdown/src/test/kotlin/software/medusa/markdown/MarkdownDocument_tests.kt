package software.medusa.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.commons.unicode.ControlChar

class MarkdownDocument_tests {
  @Test
  fun parse_buildsChapterTreeWithIntroBlocksAndInlineContent() {
    val document =
        MarkdownDocument.parse(
            """
            # Welcome to [Medusa](https://example.com)

            Intro with `code` and *focus*.

            ```kotlin
            val answer = 42
            ```

            ## Getting Started

            Start with **confidence**.

            ### Install

            Use [the guide](https://example.com/install "Install").
            """
                .trimIndent(),
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title =
                            listOf(
                                MarkdownInline.Text("Welcome to "),
                                MarkdownInline.Link(
                                    destination = "https://example.com",
                                    title = null,
                                    content = listOf(MarkdownInline.Text("Medusa")),
                                ),
                            ),
                        introBlocks =
                            listOf(
                                MarkdownBlock.Paragraph(
                                    inlineContent =
                                        listOf(
                                            MarkdownInline.Text("Intro with "),
                                            MarkdownInline.Code("code"),
                                            MarkdownInline.Text(" and "),
                                            MarkdownInline.Emphasis(
                                                listOf(MarkdownInline.Text("focus")),
                                            ),
                                            MarkdownInline.Text("."),
                                        ),
                                ),
                                MarkdownBlock.CodeBlock(
                                    code = "val answer = 42\n",
                                    info = "kotlin",
                                ),
                            ),
                        subChapters =
                            listOf(
                                MarkdownChapter(
                                    title = listOf(MarkdownInline.Text("Getting Started")),
                                    introBlocks =
                                        listOf(
                                            MarkdownBlock.Paragraph(
                                                inlineContent =
                                                    listOf(
                                                        MarkdownInline.Text("Start with "),
                                                        MarkdownInline.Strong(
                                                            listOf(
                                                                MarkdownInline.Text("confidence")
                                                            ),
                                                        ),
                                                        MarkdownInline.Text("."),
                                                    ),
                                            ),
                                        ),
                                    subChapters =
                                        listOf(
                                            MarkdownChapter(
                                                title = listOf(MarkdownInline.Text("Install")),
                                                introBlocks =
                                                    listOf(
                                                        MarkdownBlock.Paragraph(
                                                            inlineContent =
                                                                listOf(
                                                                    MarkdownInline.Text("Use "),
                                                                    MarkdownInline.Link(
                                                                        destination =
                                                                            "https://example.com/install",
                                                                        title = "Install",
                                                                        content =
                                                                            listOf(
                                                                                MarkdownInline.Text(
                                                                                    "the guide",
                                                                                ),
                                                                            ),
                                                                    ),
                                                                    MarkdownInline.Text("."),
                                                                ),
                                                        ),
                                                    ),
                                                subChapters = emptyList(),
                                            ),
                                        ),
                                ),
                            ),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_supportsRawCcCodeBlocks() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              appendLine(ControlChar.STX)
              appendLine("first line")
              appendLine()
              appendLine("second line with ``` and **literal** text")
              append(ControlChar.ETX)
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks =
                            listOf(
                                MarkdownBlock.RawCodeBlock(
                                    code =
                                        """
                                        first line

                                        second line with ``` and **literal** text
                                        """
                                            .trimIndent() + "\n",
                                ),
                            ),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_supportsEmptyRawCcCodeBlocks() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              appendLine(ControlChar.STX)
              append(ControlChar.ETX)
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks = listOf(MarkdownBlock.RawCodeBlock(code = "")),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_rawCcCodeBlockKeepsFencedCodeSyntaxLiteral() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              appendLine(ControlChar.STX)
              appendLine("```java")
              appendLine("code")
              appendLine("```")
              append(ControlChar.ETX)
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks =
                            listOf(
                                MarkdownBlock.RawCodeBlock(
                                    code =
                                        """
                                        ```java
                                        code
                                        ```
                                        """
                                            .trimIndent() + "\n",
                                ),
                            ),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_rawCcCodeBlockWithNonClosingEtxLineKeepsThatLineLiteral() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              appendLine(ControlChar.STX)
              appendLine("code")
              append(ControlChar.ETX)
              appendLine(" a")
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks =
                            listOf(
                                MarkdownBlock.RawCodeBlock(
                                    code = "code\n${ControlChar.ETX} a\n",
                                ),
                            ),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_rawCcCodeBlockMayBeIndentedUpToThreeSpaces() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              append("   ")
              appendLine(ControlChar.STX)
              appendLine("code")
              append("   ")
              append(ControlChar.ETX)
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks = listOf(MarkdownBlock.RawCodeBlock(code = "code\n")),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_fourSpaceIndentedStxDoesNotOpenRawCcCodeBlock() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              append("    ")
              appendLine(ControlChar.STX)
              appendLine("code")
              append(ControlChar.ETX)
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks =
                            listOf(
                                MarkdownBlock.CodeBlock(code = "${ControlChar.STX}\n", info = null),
                                MarkdownBlock.Paragraph(
                                    inlineContent =
                                        listOf(
                                            MarkdownInline.Text("code"),
                                            MarkdownInline.SoftBreak,
                                            MarkdownInline.Text(ControlChar.ETX.toString()),
                                        ),
                                ),
                            ),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_rawCcCodeBlockClosingIndentedTooFarDoesNotClose() {
    val document =
        MarkdownDocument.parse(
            buildString {
              appendLine("# Root")
              appendLine()
              appendLine(ControlChar.STX)
              appendLine("code")
              append("    ")
              appendLine(ControlChar.ETX)
              append("end")
            },
        )

    assertEquals(
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter(
                        title = listOf(MarkdownInline.Text("Root")),
                        introBlocks =
                            listOf(
                                MarkdownBlock.RawCodeBlock(
                                    code = "code\n    ${ControlChar.ETX}\nend\n",
                                ),
                            ),
                        subChapters = emptyList(),
                    ),
                ),
        ),
        document,
    )
  }

  @Test
  fun parse_rejectsHeadingLevelSkips() {
    val exception =
        assertFailsWith<MarkdownParseException> {
          MarkdownDocument.parse(
              """
              # Root

              ### Too Deep
              """
                  .trimIndent(),
          )
        }

    assertEquals("Invalid heading level 3; expected 1 or 2", exception.message)
  }

  @Test
  fun parse_rejectsDocumentWithoutTopLevelHeading() {
    val exception =
        assertFailsWith<MarkdownParseException> {
          MarkdownDocument.parse("Paragraph before heading")
        }

    assertEquals("Expected ATX heading: Paragraph", exception.message)
  }

  @Test
  fun parse_rejectsNestedHeadingThatSkipsOneLevel() {
    val exception =
        assertFailsWith<MarkdownParseException> {
          MarkdownDocument.parse(
              """
              # Root

              ## Child

              #### Too Deep
              """
                  .trimIndent(),
          )
        }

    assertEquals("Invalid heading level 4; expected 2 or 3", exception.message)
  }

  @Test
  fun parse_rejectsUnsupportedTopLevelStructures() {
    val exception =
        assertFailsWith<MarkdownParseException> {
          MarkdownDocument.parse(
              """
              # Root

              - item
              """
                  .trimIndent(),
          )
        }

    assertEquals("Unsupported top-level node: BulletList", exception.message)
  }

  @Test
  fun parse_rejectsUnsupportedInlineStructures() {
    val exception =
        assertFailsWith<MarkdownParseException> {
          MarkdownDocument.parse(
              """
              # Root ![alt](https://example.com/image.png)
              """
                  .trimIndent(),
          )
        }

    assertEquals("Unsupported inline node: Image", exception.message)
  }
}
