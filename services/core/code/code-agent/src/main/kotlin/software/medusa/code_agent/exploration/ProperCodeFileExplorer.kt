package software.medusa.code_agent.exploration

import software.medusa.code_agent.exploration.CodeFileExplorer.ExplorationResult
import software.medusa.code_agent.exploration.MarkdownEncodingUtils.IllegalMarkdownEncodingException
import software.medusa.code_agent.exploration.MarkdownEncodingUtils.KeyValueMap
import software.medusa.code_agent.structure.CodeFileStructure
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.unicode.ControlChar
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

class ProperCodeFileExplorer(
    private val openAiClient: OpenAiClient,
) : CodeFileExplorer {
  companion object {
    internal fun TechFileContent.Code.encodeToMarkdownDocument(
        fileName: UnixPath.Name.Literal,
    ): MarkdownDocument =
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter.leaf(
                        title =
                            listOf(
                                MarkdownInline.Text("File "),
                                MarkdownInline.Code(fileName.name),
                            ),
                        blocks =
                            listOf(
                                MarkdownBlock.CodeBlock(
                                    code =
                                        indexedLines.joinToString(separator = "") {
                                            (lineIndex, line) ->
                                          "${lineIndex.indexOneBased}${ControlChar.RS}${line.content}\n"
                                        },
                                ),
                            ),
                    ),
                ),
        )

    private const val inputFileLineSeparatorChar = ControlChar.RS

    private const val rootTitleText = "File analysis"

    internal fun ExplorationResult.Companion.decodeFromMarkdownDocument(
        document: MarkdownDocument,
    ): ExplorationResult {
      val rootChapter =
          MarkdownEncodingUtils.extractSingleTopLevelChapter(
              document = document,
          )

      if (rootChapter.title != listOf(MarkdownInline.Text(rootTitleText))) {
        throw IllegalMarkdownEncodingException(
            "Unexpected root chapter title: ${rootChapter.title}"
        )
      }

      val subChapters =
          MarkdownEncodingUtils.extractSubChaptersNotExpectingIntroBlocks(
              chapter = rootChapter,
          )

      val structureChapter =
          subChapters.singleOrNull { it.title == listOf(MarkdownInline.Text(structureTitleText)) }
              ?: throw IllegalMarkdownEncodingException("Missing '$structureTitleText' chapter")

      val summaryChapter =
          subChapters.singleOrNull { it.title == listOf(MarkdownInline.Text(summaryTitleText)) }
              ?: throw IllegalMarkdownEncodingException("Missing '$summaryTitleText' chapter")

      return ExplorationResult(
          fileSummary = decodeFileSummaryFromMarkdownChapter(chapter = summaryChapter),
          fileStructure = CodeFileStructure.decodeFromMarkdownChapter(chapter = structureChapter),
      )
    }

    internal fun ExplorationResult.encodeToMarkdownDocument(): MarkdownDocument =
        MarkdownDocument(
            chapters =
                listOf(
                    MarkdownChapter.wrapper(
                        title =
                            listOf(
                                MarkdownInline.Text(rootTitleText),
                            ),
                        subChapters =
                            listOf(
                                fileStructure.encodeToMarkdownChapter(),
                                encodeFileSummaryToMarkdownChapter(fileSummary = fileSummary),
                            ),
                    ),
                ),
        )

    private const val structureTitleText = "Structure"

    internal fun CodeFileStructure.Companion.decodeFromMarkdownChapter(
        chapter: MarkdownChapter,
    ): CodeFileStructure {
      if (chapter.title != listOf(MarkdownInline.Text(structureTitleText))) {
        throw IllegalMarkdownEncodingException(
            "Unexpected structure chapter title: ${chapter.title}"
        )
      }

      require(chapter.subChapters.isEmpty()) { "Structure chapter must be a leaf chapter" }

      val rootListBlock =
          chapter.blocks.singleOrNull() as? MarkdownBlock.ListBlock
              ?: throw IllegalMarkdownEncodingException(
                  "Structure chapter must contain one list block"
              )

      return CodeFileStructure(
          topLevelSectionStructureBySymbol =
              KeyValueMap.decodeFromListBlock(rootListBlock)
                  .entries
                  .associate(::decodeSectionEntry),
      )
    }

    internal fun CodeFileStructure.encodeToMarkdownChapter(): MarkdownChapter =
        MarkdownChapter.leaf(
            title =
                listOf(
                    MarkdownInline.Text(text = structureTitleText),
                ),
            blocks =
                listOf(
                    KeyValueMap(
                            entries =
                                topLevelSectionStructureBySymbol.map {
                                    (topLevelSymbol, topLevelSectionStructure) ->
                                  topLevelSectionStructure.encodeToKeyValueEntry(
                                      symbol = topLevelSymbol
                                  )
                                },
                        )
                        .encodeToListBlock(),
                ),
        )

    private const val summaryTitleText = "Summary"

    internal fun encodeFileSummaryToMarkdownChapter(
        fileSummary: MarkdownBlock.Paragraph,
    ): MarkdownChapter =
        MarkdownChapter.leaf(
            title =
                listOf(
                    MarkdownInline.Text(text = summaryTitleText),
                ),
            blocks = listOf(fileSummary),
        )

    private const val sectionKey = "SECTION"
    private const val nameKey = "NAME"
    private const val rangeKey = "RANGE"
    private const val summaryKey = "SUMMARY"
    private const val nestedKey = "NESTED"

    internal fun CodeFileStructure.SectionStructure.encodeToKeyValueEntry(
        symbol: CodeFileStructure.Symbol,
    ): KeyValueMap.Entry =
        KeyValueMap.Entry(
            key = sectionKey,
            node =
                KeyValueMap.Group(
                    entries =
                        listOfNotNull(
                            KeyValueMap.Entry(
                                key = nameKey,
                                node = symbol.encodeToInlineValue(),
                            ),
                            KeyValueMap.Entry(
                                key = rangeKey,
                                node = coveredRange.encodeToPlainRecord(),
                            ),
                            KeyValueMap.Entry(
                                key = summaryKey,
                                node = KeyValueMap.InlineValue(sectionSummary.inlineContent),
                            ),
                            nestedSectionStructureBySymbol
                                .takeIf { it.isNotEmpty() }
                                ?.let { nonEmptyMap ->
                                  KeyValueMap.Entry(
                                      key = nestedKey,
                                      node =
                                          KeyValueMap.Group(
                                              nonEmptyMap.map {
                                                  (nestedSymbol, nestedSectionStructure) ->
                                                nestedSectionStructure.encodeToKeyValueEntry(
                                                    symbol = nestedSymbol
                                                )
                                              },
                                          ),
                                  )
                                },
                        ),
                ),
        )

    private const val importBlockSpecialName = "#import_block"

    internal fun CodeFileStructure.Symbol.encodeToInlineValue(): KeyValueMap.InlineValue =
        KeyValueMap.InlineValue(
            inlineContent =
                listOf(
                    MarkdownInline.Code(
                        when (this) {
                          is CodeFileStructure.EntityNameSymbol -> name
                          CodeFileStructure.ImportBlockSymbol -> importBlockSpecialName
                        },
                    ),
                ),
        )

    internal fun LineIndexRange.encodeToPlainRecord(): KeyValueMap.InlineValue =
        KeyValueMap.InlineValue(
            inlineContent =
                listOf(
                    MarkdownInline.Text(
                        "${startIndex.indexOneBased}-${endIndexExclusive.indexOneBased - 1}",
                    ),
                ),
        )

    private fun decodeFileSummaryFromMarkdownChapter(
        chapter: MarkdownChapter,
    ): MarkdownBlock.Paragraph {
      if (chapter.title != listOf(MarkdownInline.Text(summaryTitleText))) {
        throw IllegalMarkdownEncodingException("Unexpected summary chapter title: ${chapter.title}")
      }

      require(chapter.subChapters.isEmpty()) { "Summary chapter must be a leaf chapter" }

      return chapter.blocks.singleOrNull() as? MarkdownBlock.Paragraph
          ?: throw IllegalMarkdownEncodingException("Summary chapter must contain one paragraph")
    }

    private fun decodeSectionEntry(
        entry: KeyValueMap.Entry,
    ): Pair<CodeFileStructure.Symbol, CodeFileStructure.SectionStructure> {
      require(entry.key == sectionKey) { "Unexpected section entry key: ${entry.key}" }

      val sectionGroup =
          entry.node as? KeyValueMap.Group
              ?: throw IllegalMarkdownEncodingException("Section entry must contain a nested group")

      val nestedEntriesByKey = sectionGroup.map.entries.groupBy { it.key }

      val symbol = decodeSymbol(entry = nestedEntriesByKey.requireSingle(nameKey))
      val coveredRange = decodeLineRange(entry = nestedEntriesByKey.requireSingle(rangeKey))
      val sectionSummary = decodeParagraph(entry = nestedEntriesByKey.requireSingle(summaryKey))
      val nestedSectionStructureBySymbol =
          nestedEntriesByKey[nestedKey]?.singleOrNull()?.let(::decodeNestedSections) ?: emptyMap()

      return symbol to
          CodeFileStructure.SectionStructure(
              coveredRange = coveredRange,
              sectionSummary = sectionSummary,
              nestedSectionStructureBySymbol = nestedSectionStructureBySymbol,
          )
    }

    private fun decodeSymbol(
        entry: KeyValueMap.Entry,
    ): CodeFileStructure.Symbol {
      val rawRecord =
          entry.node as? KeyValueMap.InlineValue
              ?: throw IllegalMarkdownEncodingException(
                  "Name entry must be encoded as inline value"
              )

      val codeInline =
          rawRecord.inlineContent.singleOrNull() as? MarkdownInline.Code
              ?: throw IllegalMarkdownEncodingException(
                  "Name entry must contain exactly one inline code node"
              )

      return when (codeInline.code) {
        importBlockSpecialName -> CodeFileStructure.ImportBlockSymbol
        else -> CodeFileStructure.EntityNameSymbol(codeInline.code)
      }
    }

    private fun decodeLineRange(
        entry: KeyValueMap.Entry,
    ): LineIndexRange {
      val plainRecord =
          entry.node as? KeyValueMap.InlineValue
              ?: throw IllegalMarkdownEncodingException(
                  "Range entry must be encoded as inline value"
              )

      val plainText = plainRecord.inlineContent.toPlainText()

      val matchResult =
          Regex("^(\\d+)-(\\d+)$").matchEntire(plainText)
              ?: throw IllegalMarkdownEncodingException("Invalid line range: $plainText")

      val (startLineText, endLineText) = matchResult.destructured
      val startLine = startLineText.toInt()
      val endLineInclusive = endLineText.toInt()

      return LineIndexRange(
          startIndex = CodeBlock.LineIndex.ofOneBased(startLine),
          endIndexExclusive = CodeBlock.LineIndex.ofOneBased(endLineInclusive + 1),
      )
    }

    private fun decodeParagraph(
        entry: KeyValueMap.Entry,
    ): MarkdownBlock.Paragraph {
      val node =
          entry.node as? KeyValueMap.InlineValue
              ?: throw IllegalMarkdownEncodingException(
                  "Summary entry must be encoded as inline value"
              )

      return MarkdownBlock.Paragraph(
          inlineContent = node.inlineContent,
      )
    }

    private fun decodeNestedSections(
        entry: KeyValueMap.Entry,
    ): Map<CodeFileStructure.Symbol, CodeFileStructure.SectionStructure> {
      val group =
          entry.node as? KeyValueMap.Group
              ?: throw IllegalMarkdownEncodingException("Nested entry must contain a group")

      return group.map.entries.associate(::decodeSectionEntry)
    }

    private fun Map<String, List<KeyValueMap.Entry>>.requireSingle(
        key: String,
    ): KeyValueMap.Entry =
        get(key)?.singleOrNull()
            ?: throw IllegalMarkdownEncodingException("Missing or duplicated '$key' entry")

    private fun List<MarkdownInline>.toPlainText(): String =
        joinToString(separator = "") { inline ->
          when (inline) {
            is MarkdownInline.Text -> inline.text
            is MarkdownInline.Code -> inline.code
            is MarkdownInline.Emphasis -> inline.content.toPlainText()
            is MarkdownInline.Strong -> inline.content.toPlainText()
            is MarkdownInline.Link -> inline.content.toPlainText()
            MarkdownInline.SoftBreak -> "\n"
            MarkdownInline.HardBreak -> "\n"
          }
        }

    private val explorationModel = OpenAiModel.Gemma12B

    private val instructionPrompt =
        CodeBlock.of(
            "Analyze the user-provided file and describe its high-level structure.",
            "",
            "Input file format:",
            "- The file content is provided as a code block with line numbers.",
            "- The separator character (ASCII Record Separator) is used to separate line numbers from line content.",
            "- Neither line numbers nor separator characters are part of the literal file content",
            "",
            "Goals:",
            "- Identify meaningful sections that represent the file's main structural units.",
            "- Prefer coarse, useful sections over tiny fragments.",
            "- Do not over-segment: extracting very small sections is a non-goal unless they are clearly important named entities.",
            "- The output should help a consumer understand the file's structure at a glance.",
            "",
            "Section selection rules:",
            "- For code files:",
            "  - Designate the import block, if present, using the special name `#import_block`.",
            "  - Designate each important named entity section (for example: module, class, interface, struct, enum, function, method, type, trait, object, component, etc.).",
            "  - Include nested sections when they are meaningful.",
            "  - Nesting may continue to any depth when the file structure warrants it.",
            "  - Do not list every small internal block.",
            "- For configuration or structured text files:",
            "  - Designate the crucial top-level sections or groups.",
            "  - Do not create a section for every key or trivial entry when there are many.",
            "",
            "Summary rules:",
            "- Every section must include a summary.",
            "- Every section summary must be exactly one line.",
            "- The overall file summary must be exactly one line.",
            "- Do not use bullets, line breaks, or paragraphs inside any summary.",
            "- Summaries should be concise but sufficiently informative; they do not need to be unnaturally short.",
            "",
            "Return the response in the following form:",
            "- Start with the heading `# File Analysis`.",
            "- Then include the heading `## Structure`.",
            "- Under `## Structure`, output a tree of sections.",
            "- Each section is written as a `SECTION` item with:",
            "  - `NAME`: <the section name>",
            "  - `RANGE`: <the inclusive line range as `<start line>-<end line>`>",
            "  - `SUMMARY`: <exactly one line>",
            "- A section may contain `NESTED`, which is a list of child sections.",
            "- Child sections use the same `SECTION` structure as their parent.",
            "- This nesting is recursive and may continue to any depth.",
            "- After the structure tree, include the heading `## Summary` followed by exactly one line summarizing the whole file.",
        )

    @Suppress("CanConvertToMultiDollarString")
    private val exampleFileContent =
        TechFileContent.Code(
            code =
                CodeBlock.of(
                    /* 01 */ "import java.time.Instant",
                    /* 02 */ "",
                    /* 03 */ "val DEFAULT_LABEL = \"unknown\"",
                    /* 04 */ "",
                    /* 05 */ "class OuterService(",
                    /* 06 */ "    private val createdAt: Instant,",
                    /* 07 */ ") {",
                    /* 08 */ "    fun formatLabel(): String {",
                    /* 09 */ "        return \"[\$createdAt] \$DEFAULT_LABEL\"",
                    /* 10 */ "    }",
                    /* 11 */ "",
                    /* 12 */ "    class Formatter {",
                    /* 13 */ "        fun render(value: String): String {",
                    /* 14 */ "            return value.trim()",
                    /* 15 */ "        }",
                    /* 16 */ "    }",
                    /* 17 */ "}",
                ),
        )

    private val exampleExplorationResult =
        ExplorationResult(
            fileSummary =
                MarkdownBlock.Paragraph(
                    inlineContent =
                        listOf(
                            MarkdownInline.Text(
                                "This file defines a top-level label constant and an OuterService class with a formatting method and a nested Formatter class.",
                            ),
                        ),
                ),
            fileStructure =
                CodeFileStructure(
                    topLevelSectionStructureBySymbol =
                        mapOf(
                            CodeFileStructure.EntityNameSymbol("#import_block") to
                                CodeFileStructure.SectionStructure(
                                    coveredRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(1),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(2),
                                        ),
                                    sectionSummary =
                                        MarkdownBlock.Paragraph(
                                            inlineContent =
                                                listOf(
                                                    MarkdownInline.Text(
                                                        "Imports Instant for use in the service class."
                                                    ),
                                                ),
                                        ),
                                    nestedSectionStructureBySymbol = emptyMap(),
                                ),
                            CodeFileStructure.EntityNameSymbol("DEFAULT_LABEL") to
                                CodeFileStructure.SectionStructure(
                                    coveredRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(3),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(4),
                                        ),
                                    sectionSummary =
                                        MarkdownBlock.Paragraph(
                                            inlineContent =
                                                listOf(
                                                    MarkdownInline.Text(
                                                        "Defines a top-level default label constant."
                                                    ),
                                                ),
                                        ),
                                    nestedSectionStructureBySymbol = emptyMap(),
                                ),
                            CodeFileStructure.EntityNameSymbol("OuterService") to
                                CodeFileStructure.SectionStructure(
                                    coveredRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(5),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(17),
                                        ),
                                    sectionSummary =
                                        MarkdownBlock.Paragraph(
                                            inlineContent =
                                                listOf(
                                                    MarkdownInline.Text(
                                                        "Defines a service class with a label-formatting method and a nested formatter type."
                                                    ),
                                                ),
                                        ),
                                    nestedSectionStructureBySymbol =
                                        mapOf(
                                            CodeFileStructure.EntityNameSymbol("formatLabel") to
                                                CodeFileStructure.SectionStructure(
                                                    coveredRange =
                                                        CodeBlock.LineIndexRange(
                                                            startIndex =
                                                                CodeBlock.LineIndex.ofOneBased(8),
                                                            endIndexExclusive =
                                                                CodeBlock.LineIndex.ofOneBased(11),
                                                        ),
                                                    sectionSummary =
                                                        MarkdownBlock.Paragraph(
                                                            inlineContent =
                                                                listOf(
                                                                    MarkdownInline.Text(
                                                                        "Builds a formatted label string using the creation time and default label."
                                                                    ),
                                                                ),
                                                        ),
                                                    nestedSectionStructureBySymbol = emptyMap(),
                                                ),
                                            CodeFileStructure.EntityNameSymbol("Formatter") to
                                                CodeFileStructure.SectionStructure(
                                                    coveredRange =
                                                        CodeBlock.LineIndexRange(
                                                            startIndex =
                                                                CodeBlock.LineIndex.ofOneBased(12),
                                                            endIndexExclusive =
                                                                CodeBlock.LineIndex.ofOneBased(16),
                                                        ),
                                                    sectionSummary =
                                                        MarkdownBlock.Paragraph(
                                                            inlineContent =
                                                                listOf(
                                                                    MarkdownInline.Text(
                                                                        "Defines a nested helper class for rendering string values."
                                                                    ),
                                                                ),
                                                        ),
                                                    nestedSectionStructureBySymbol =
                                                        mapOf(
                                                            CodeFileStructure.EntityNameSymbol(
                                                                "render"
                                                            ) to
                                                                CodeFileStructure.SectionStructure(
                                                                    coveredRange =
                                                                        CodeBlock.LineIndexRange(
                                                                            startIndex =
                                                                                CodeBlock.LineIndex
                                                                                    .ofOneBased(
                                                                                        13,
                                                                                    ),
                                                                            endIndexExclusive =
                                                                                CodeBlock.LineIndex
                                                                                    .ofOneBased(
                                                                                        15,
                                                                                    ),
                                                                        ),
                                                                    sectionSummary =
                                                                        MarkdownBlock.Paragraph(
                                                                            inlineContent =
                                                                                listOf(
                                                                                    MarkdownInline
                                                                                        .Text(
                                                                                            "Returns a trimmed version of the provided string."
                                                                                        ),
                                                                                ),
                                                                        ),
                                                                    nestedSectionStructureBySymbol =
                                                                        emptyMap(),
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )
  }

  override suspend fun exploreFile(
      fileName: UnixPath.Name.Literal,
      fileContent: TechFileContent.Code,
  ): ExplorationResult {
    val inputChat =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text = instructionPrompt.dump(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text =
                            exampleFileContent
                                .encodeToMarkdownDocument(
                                    fileName = fileName,
                                )
                                .toMarkdownString(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text =
                            exampleExplorationResult.encodeToMarkdownDocument().toMarkdownString(),
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text =
                            fileContent
                                .encodeToMarkdownDocument(
                                    fileName = fileName,
                                )
                                .toMarkdownString(),
                    ),
                ),
        )

    val response =
        openAiClient.createUnstructuredCompletion(
            request =
                OpenAiClient.CompletionRequest(
                    input = inputChat,
                    model = explorationModel,
                ),
        )

    val responseMarkdownDocument =
        MarkdownDocument.parse(
            markdown = response.responseText,
        )

    val responseExplorationResult =
        ExplorationResult.decodeFromMarkdownDocument(
            document = responseMarkdownDocument,
        )

    return responseExplorationResult
  }
}
