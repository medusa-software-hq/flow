package software.medusa.code_agent.exploration

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import software.medusa.code_agent.exploration.ProperCodeFileExplorer.Companion.encodeToMarkdownDocument
import software.medusa.code_agent.structure.CodeFileStructure
import software.medusa.commons.code.CodeBlock
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline

/*
EXAMPLE:

# File Analysis

## Structure

- SECTION:
  - NAME: `Foo`
  - RANGE: 4-19
  - SUMMARY: Foo class summary goes here.
  - NESTED:
    - SECTION:
      - NAME: `f1`
      - RANGE: 8-12
      - SUMMARY: f1 is a function and it is a nice function.

## Summary

This file is a nice file I sum up.

It has interesting content and stuff.

 */
class ProperCodeFileExplorer_tests {
  companion object {
    private fun paragraph(text: String): MarkdownBlock.Paragraph =
        MarkdownBlock.Paragraph(
            inlineContent = listOf(MarkdownInline.Text(text)),
        )

    private fun expectedTextValueItem(
        key: String,
        value: String,
    ): MarkdownBlock.ListBlock.Item =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    MarkdownBlock.Paragraph(
                        inlineContent =
                            listOf(
                                MarkdownInline.Text("$key:"),
                                MarkdownInline.Text(" "),
                                MarkdownInline.Text(value),
                            ),
                    ),
                ),
        )

    private fun expectedCodeValueItem(
        key: String,
        value: String,
    ): MarkdownBlock.ListBlock.Item =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    MarkdownBlock.Paragraph(
                        inlineContent =
                            listOf(
                                MarkdownInline.Text("$key:"),
                                MarkdownInline.Text(" "),
                                MarkdownInline.Code(value),
                            ),
                    ),
                ),
        )
  }

  @Test
  fun test_encodeToMarkdownDocument() {
    val f1SectionStructure =
        CodeFileStructure.SectionStructure(
            coveredRange =
                CodeBlock.LineIndexRange(
                    startIndex = CodeBlock.LineIndex.ofOneBased(6),
                    endIndexExclusive = CodeBlock.LineIndex.ofOneBased(9),
                ),
            sectionSummary = paragraph("A very useful f1 function computing useful results."),
            nestedSectionStructureBySymbol = emptyMap(),
        )

    val f2SectionStructure =
        CodeFileStructure.SectionStructure(
            coveredRange =
                CodeBlock.LineIndexRange(
                    startIndex = CodeBlock.LineIndex.ofOneBased(14),
                    endIndexExclusive = CodeBlock.LineIndex.ofOneBased(17),
                ),
            sectionSummary =
                paragraph("A very useful f2 function computing even more useful results."),
            nestedSectionStructureBySymbol = emptyMap(),
        )

    val fooSectionStructure =
        CodeFileStructure.SectionStructure(
            coveredRange =
                CodeBlock.LineIndexRange(
                    startIndex = CodeBlock.LineIndex.ofOneBased(5),
                    endIndexExclusive = CodeBlock.LineIndex.ofOneBased(19),
                ),
            sectionSummary = paragraph("A very useful Foo class doing useful foo things."),
            nestedSectionStructureBySymbol =
                mapOf(
                    CodeFileStructure.EntityNameSymbol("f1") to f1SectionStructure,
                    CodeFileStructure.EntityNameSymbol("f2") to f2SectionStructure,
                ),
        )

    val fileStructure =
        CodeFileStructure(
            topLevelSectionStructureBySymbol =
                mapOf(
                    CodeFileStructure.EntityNameSymbol("Foo") to fooSectionStructure,
                ),
        )

    val explorationResult =
        CodeFileExplorer.ExplorationResult(
            fileSummary = paragraph("A useful code file with some useful code."),
            fileStructure = fileStructure,
        )

    val expectedFooNameItem =
        expectedCodeValueItem(
            key = "NAME",
            value = "Foo",
        )

    val expectedFooSummaryItem =
        expectedTextValueItem(
            key = "SUMMARY",
            value = "A very useful Foo class doing useful foo things.",
        )

    val expectedF1SectionItem =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    paragraph("SECTION:"),
                    MarkdownBlock.ListBlock(
                        items =
                            listOf(
                                MarkdownBlock.ListBlock.Item(
                                    blocks = expectedCodeValueItem("NAME", "f1").blocks,
                                ),
                                expectedTextValueItem("RANGE", "6-8"),
                                expectedTextValueItem(
                                    "SUMMARY",
                                    "A very useful f1 function computing useful results.",
                                ),
                            ),
                    ),
                ),
        )

    val expectedF2SectionItem =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    paragraph("SECTION:"),
                    MarkdownBlock.ListBlock(
                        items =
                            listOf(
                                MarkdownBlock.ListBlock.Item(
                                    blocks = expectedCodeValueItem("NAME", "f2").blocks,
                                ),
                                expectedTextValueItem("RANGE", "14-16"),
                                expectedTextValueItem(
                                    "SUMMARY",
                                    "A very useful f2 function computing even more useful results.",
                                ),
                            ),
                    ),
                ),
        )

    val expectedFooNestedItem =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    paragraph("NESTED:"),
                    MarkdownBlock.ListBlock(
                        items = listOf(expectedF1SectionItem, expectedF2SectionItem),
                    ),
                ),
        )

    val expectedFooSectionItem =
        MarkdownBlock.ListBlock.Item(
            blocks =
                listOf(
                    paragraph("SECTION:"),
                    MarkdownBlock.ListBlock(
                        items =
                            listOf(
                                expectedFooNameItem,
                                expectedTextValueItem("RANGE", "5-18"),
                                expectedFooSummaryItem,
                                expectedFooNestedItem,
                            ),
                    ),
                ),
        )

    val expectedMarkdownDocument =
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter.wrapper(
                        title =
                            listOf(
                                MarkdownInline.Text("File analysis"),
                            ),
                        subChapters =
                            listOf(
                                MarkdownChapter.leaf(
                                    title =
                                        listOf(
                                            MarkdownInline.Text("Structure"),
                                        ),
                                    blocks =
                                        listOf(
                                            MarkdownBlock.ListBlock(
                                                items = listOf(expectedFooSectionItem),
                                            ),
                                        ),
                                ),
                                MarkdownChapter.leaf(
                                    title =
                                        listOf(
                                            MarkdownInline.Text("Summary"),
                                        ),
                                    blocks =
                                        listOf(
                                            paragraph("A useful code file with some useful code."),
                                        ),
                                ),
                            ),
                    ),
                ),
        )

    val actualMarkdownDocument = explorationResult.encodeToMarkdownDocument()

    assertEquals(
        expected = expectedMarkdownDocument,
        actual = actualMarkdownDocument,
    )
  }
}
