package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.commons.unicode.ControlChar
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline

internal data object CcMdAiCodePatcher_wire_utils {
  private const val patchSetTitle = "PATCHSET"
  private const val planTitle = "PLAN"
  private const val executionTitle = "EXECUTION"
  private const val updatePrefix = "UPDATE "
  private const val createPrefix = "CREATE "
  private val replaceRegex = Regex("^REPLACE (\\d+)-(\\d+)$")
  private val deleteRegex = Regex("^DELETE (\\d+)-(\\d+)$")
  private val insertBeforeRegex = Regex("^INSERT BEFORE (\\d+)$")
  private val insertAfterRegex = Regex("^INSERT AFTER (\\d+)$")

  data class BodyAstNode(
      val filePatches: List<FilePatchAstNode>,
  )

  sealed interface FilePatchAstNode {
    val filePath: String

    data class Update(
        override val filePath: String,
        val patchFragments: List<PatchFragmentAstNode>,
    ) : FilePatchAstNode

    data class Create(
        override val filePath: String,
        val rawContent: String,
    ) : FilePatchAstNode
  }

  sealed interface PatchFragmentAstNode {
    data class InsertBefore(
        val laterLineNumber: Int,
        val rawContent: String,
    ) : PatchFragmentAstNode

    data class InsertAfter(
        val earlierLineNumber: Int,
        val rawContent: String,
    ) : PatchFragmentAstNode

    data class Replace(
        val startLineNumber: Int,
        val endLineNumberInclusive: Int,
        val rawContent: String,
    ) : PatchFragmentAstNode

    data class Delete(
        val startLineNumber: Int,
        val endLineNumberInclusive: Int,
    ) : PatchFragmentAstNode
  }

  val responseStructureDescription: String =
      """
      Response must be Markdown with this chapter structure:

      # $patchSetTitle
      ## $planTitle
      Optional explanatory prose. This section is ignored by the parser.

      ## $executionTitle
      ### UPDATE <relative/unix/path>
      #### REPLACE <start>-<end>
      ${ControlChar.STX}
      replacement lines here
      ${ControlChar.ETX}

      #### DELETE <start>-<end>

      #### INSERT BEFORE <line>
      ${ControlChar.STX}
      inserted lines here
      ${ControlChar.ETX}

      #### INSERT AFTER <line>
      ${ControlChar.STX}
      inserted lines here
      ${ControlChar.ETX}

      ### CREATE <relative/unix/path>
      ${ControlChar.STX}
      full replacement file content here
      ${ControlChar.ETX}

      Rules:
      - Use literal ASCII STX and ETX control characters, not text placeholders.
      - STX and ETX must each appear alone on their own line, not in backtick fences.
      - Line numbers are 1-based and refer to the original file content.
      - Prefer the smallest local changes that complete the task instead of rewriting large unchanged regions.
      - $planTitle is optional and ignored.
      - $createPrefix is allowed only for file paths present in the input catalog and means replacing the full file content.
      - For CREATE, REPLACE, INSERT BEFORE, and INSERT AFTER, include exactly one raw code block.
      - DELETE chapters must not contain a code block.
      """
          .trimIndent()

  val examplePatchSetMarkdownText: String = buildString {
    appendLine("# $patchSetTitle")
    appendLine()
    appendLine("## $planTitle")
    appendLine()
    appendLine("Adjust the config while preserving the remaining structure.")
    appendLine()
    appendLine("## $executionTitle")
    appendLine()
    appendLine("### UPDATE path/to/module.yaml")
    appendLine()
    appendLine("#### INSERT BEFORE 1")
    appendLine()
    appendLine(ControlChar.STX)
    appendLine("version: 1")
    append(ControlChar.ETX)
    appendLine()
    appendLine()
    appendLine("#### REPLACE 3-4")
    appendLine()
    appendLine(ControlChar.STX)
    appendLine("mode: strict")
    appendLine("enabled: true")
    append(ControlChar.ETX)
    appendLine()
    appendLine()
    appendLine("#### DELETE 10-12")
    appendLine()
    appendLine("### CREATE path/to/notes.md")
    appendLine()
    appendLine(ControlChar.STX)
    appendLine("# Notes")
    appendLine("Generated by the patcher")
    append(ControlChar.ETX)
  }

  fun MaskedCodeCatalog.encodeToCcMarkdownString(): String = buildString {
    appendLine("# INPUT")

    maskedCodeFileContentByPath.entries
        .sortedBy { (filePath, _) -> filePath.toUnixRelativePathString() }
        .forEach { (filePath, maskedCodeFileContent) ->
          appendLine()
          appendLine("## ${filePath.toUnixRelativePathString()}")
          appendLine()
          appendLine(ControlChar.STX)
          append(maskedCodeFileContent.toNumberedRawBlock())
          append(ControlChar.ETX)
          appendLine()
        }
  }

  fun parseChangeSet(
      responseText: String,
      maskedCodeCatalog: MaskedCodeCatalog,
  ): ChangeSet {
    val astNode = parseAst(responseText)

    return astNode.toChangeSet(maskedCodeCatalog = maskedCodeCatalog)
  }

  fun parseAst(
      responseText: String,
  ): BodyAstNode {
    val document = MarkdownDocument.parse(responseText)

    require(document.chapters.size == 1) {
      "Expected exactly one top-level chapter named '$patchSetTitle'"
    }

    val rootChapter = document.chapters.single()
    require(rootChapter.titleText == patchSetTitle) {
      "Expected top-level chapter '$patchSetTitle', but found '${rootChapter.titleText}'"
    }

    val executionChapter =
        rootChapter.subChapters.singleOrNull { chapter -> chapter.titleText == executionTitle }
            ?: throw IllegalArgumentException(
                "Expected chapter '$patchSetTitle' to contain exactly one '$executionTitle' chapter",
            )

    val unexpectedSubChapter =
        rootChapter.subChapters.firstOrNull { chapter ->
          chapter.titleText != planTitle && chapter.titleText != executionTitle
        }

    require(unexpectedSubChapter == null) {
      "Unexpected chapter under '$patchSetTitle': ${unexpectedSubChapter!!.titleText}"
    }

    return BodyAstNode(
        filePatches =
            executionChapter.subChapters.mapIndexed { filePatchIndex, filePatchChapter ->
              parseFilePatchAst(
                  chapter = filePatchChapter,
                  context = "$executionTitle[$filePatchIndex]",
              )
            },
    )
  }

  private fun BodyAstNode.toChangeSet(
      maskedCodeCatalog: MaskedCodeCatalog,
  ): ChangeSet {
    val availableRelativePaths = maskedCodeCatalog.maskedCodeFileContentByPath.keys

    val changeByFilePath = buildMap {
      for ((filePatchIndex, filePatchAstNode) in filePatches.withIndex()) {
        val (filePath, change) =
            filePatchAstNode.toChange(maskedCodeCatalog = maskedCodeCatalog).also { (filePath, _) ->
              require(filePath in availableRelativePaths) {
                "Patch references file not present in masked code catalog: ${filePath.toUnixRelativePathString()}"
              }
            }

        val previousPatch = put(filePath, change)

        require(previousPatch == null) {
          "Duplicate patch for file path ${filePath.toUnixRelativePathString()} at execution[$filePatchIndex]"
        }
      }
    }

    return ChangeSet(
        changeByFilePath = changeByFilePath,
    )
  }

  private fun parseFilePatchAst(
      chapter: MarkdownChapter,
      context: String,
  ): FilePatchAstNode {
    val title = chapter.titleText

    return when {
      title.startsWith(updatePrefix) ->
          FilePatchAstNode.Update(
                  filePath = title.removePrefix(updatePrefix),
                  patchFragments =
                      chapter.subChapters.mapIndexed { fragmentIndex, fragmentChapter ->
                        parseFragmentAst(
                            chapter = fragmentChapter,
                            context = "$context.fragments[$fragmentIndex]",
                        )
                      },
              )
              .also {
                require(chapter.introBlocks.isEmpty()) {
                  "Expected $context update chapter to have no intro blocks"
                }
              }

      title.startsWith(createPrefix) ->
          FilePatchAstNode.Create(
                  filePath = title.removePrefix(createPrefix),
                  rawContent = requireSingleRawCodeBlock(chapter = chapter, context = context),
              )
              .also {
                require(chapter.subChapters.isEmpty()) {
                  "Expected $context create chapter to have no sub-chapters"
                }
              }

      else -> throw IllegalArgumentException("Unrecognized execution chapter '$title' in $context")
    }
  }

  private fun FilePatchAstNode.toChange(
      maskedCodeCatalog: MaskedCodeCatalog,
  ): Pair<LiteralRelativeUnixPath, Change> {
    val filePath = filePath.toLiteralRelativeUnixPath()

    return when (this) {
      is FilePatchAstNode.Update ->
          filePath to
              Change.Patch(
                  fragmentByOldLineIndexRange =
                      patchFragments.associate { patchFragmentAstNode ->
                        patchFragmentAstNode.toChangeFragment()
                      },
              )

      is FilePatchAstNode.Create -> {
        val existingFileContent =
            maskedCodeCatalog.maskedCodeFileContentByPath[filePath]?.codeFileContent
                ?: throw IllegalArgumentException(
                    "Patch references file not present in masked code catalog: ${filePath.toUnixRelativePathString()}",
                )

        filePath to
            Change.Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        existingFileContent.wholeFileRange to
                            Change.Patch.Fragment(
                                newCodeBlock = rawContent.toPatchCodeBlock(),
                            ),
                    ),
            )
      }
    }
  }

  private fun PatchFragmentAstNode.toChangeFragment(): Pair<LineIndexRange, Change.Patch.Fragment> =
      when (this) {
        is PatchFragmentAstNode.InsertBefore ->
            LineIndexRange.empty(startIndex = LineIndex.ofOneBased(laterLineNumber)) to
                Change.Patch.Fragment(
                    newCodeBlock = rawContent.toPatchCodeBlock(),
                )

        is PatchFragmentAstNode.InsertAfter ->
            LineIndexRange.empty(startIndex = LineIndex.ofOneBased(earlierLineNumber).next) to
                Change.Patch.Fragment(
                    newCodeBlock = rawContent.toPatchCodeBlock(),
                )

        is PatchFragmentAstNode.Replace ->
            LineIndexRange(
                startIndex = LineIndex.ofOneBased(startLineNumber),
                endIndexExclusive = LineIndex.ofOneBased(endLineNumberInclusive).next,
            ) to
                Change.Patch.Fragment(
                    newCodeBlock = rawContent.toPatchCodeBlock(),
                )

        is PatchFragmentAstNode.Delete ->
            LineIndexRange(
                startIndex = LineIndex.ofOneBased(startLineNumber),
                endIndexExclusive = LineIndex.ofOneBased(endLineNumberInclusive).next,
            ) to Change.Patch.Fragment.Empty
      }

  private fun parseFragmentAst(
      chapter: MarkdownChapter,
      context: String,
  ): PatchFragmentAstNode {
    val title = chapter.titleText

    replaceRegex.matchEntire(title)?.destructured?.let { (start, end) ->
      require(chapter.subChapters.isEmpty()) {
        "Expected $context replace chapter to have no sub-chapters"
      }

      return PatchFragmentAstNode.Replace(
          startLineNumber = start.toStrictPositiveInt(fieldName = "start", context = context),
          endLineNumberInclusive = end.toStrictPositiveInt(fieldName = "end", context = context),
          rawContent = requireSingleRawCodeBlock(chapter = chapter, context = context),
      )
    }

    deleteRegex.matchEntire(title)?.destructured?.let { (start, end) ->
      require(chapter.introBlocks.isEmpty()) {
        "Expected $context delete chapter to have no intro blocks"
      }
      require(chapter.subChapters.isEmpty()) {
        "Expected $context delete chapter to have no sub-chapters"
      }

      return PatchFragmentAstNode.Delete(
          startLineNumber = start.toStrictPositiveInt(fieldName = "start", context = context),
          endLineNumberInclusive = end.toStrictPositiveInt(fieldName = "end", context = context),
      )
    }

    insertBeforeRegex.matchEntire(title)?.destructured?.let { (lineNumber) ->
      require(chapter.subChapters.isEmpty()) {
        "Expected $context insert-before chapter to have no sub-chapters"
      }

      return PatchFragmentAstNode.InsertBefore(
          laterLineNumber = lineNumber.toStrictPositiveInt(fieldName = "line", context = context),
          rawContent = requireSingleRawCodeBlock(chapter = chapter, context = context),
      )
    }

    insertAfterRegex.matchEntire(title)?.destructured?.let { (lineNumber) ->
      require(chapter.subChapters.isEmpty()) {
        "Expected $context insert-after chapter to have no sub-chapters"
      }

      return PatchFragmentAstNode.InsertAfter(
          earlierLineNumber = lineNumber.toStrictPositiveInt(fieldName = "line", context = context),
          rawContent = requireSingleRawCodeBlock(chapter = chapter, context = context),
      )
    }

    throw IllegalArgumentException("Unrecognized fragment chapter '$title' in $context")
  }

  private fun requireSingleRawCodeBlock(
      chapter: MarkdownChapter,
      context: String,
  ): String {
    require(chapter.introBlocks.size == 1) {
      "Expected $context to contain exactly one raw code block"
    }

    val block = chapter.introBlocks.single()

    return (block as? MarkdownBlock.RawCodeBlock)?.code
        ?: throw IllegalArgumentException("Expected $context to contain a raw code block")
  }

  private fun MaskedCodeFileContent.toNumberedRawBlock(): String = buildString {
    codeFileContent.indexedLines.forEach { indexedLine ->
      val lineRange = LineIndexRange.of(startIndex = indexedLine.index, length = 1)
      val lineText =
          when {
            mask.maskedLineRanges.any { it.overlaps(lineRange) } -> "[MASKED LINE]"
            else -> indexedLine.line.content
          }

      appendLine("${indexedLine.index.indexOneBased}: $lineText")
    }
  }

  private val MarkdownChapter.titleText: String
    get() = title.joinToString(separator = "") { inline -> inline.flattenToText() }

  private fun MarkdownInline.flattenToText(): String =
      when (this) {
        is MarkdownInline.Text -> text
        is MarkdownInline.Code -> code
        is MarkdownInline.Emphasis ->
            content.joinToString(separator = "") { inline -> inline.flattenToText() }
        is MarkdownInline.Strong ->
            content.joinToString(separator = "") { inline -> inline.flattenToText() }
        is MarkdownInline.Link ->
            content.joinToString(separator = "") { inline -> inline.flattenToText() }
        MarkdownInline.SoftBreak -> " "
        MarkdownInline.HardBreak -> "\n"
      }

  private fun String.toLiteralRelativeUnixPath(): LiteralRelativeUnixPath =
      RelativeUnixPath.parse(this).toLiteral()
          ?: throw IllegalArgumentException(
              "Patch path must consist of literal path segments: $this",
          )

  private fun String.toStrictPositiveInt(
      fieldName: String,
      context: String,
  ): Int {
    val intValue =
        toIntOrNull()
            ?: throw IllegalArgumentException(
                "Expected $context.$fieldName to be a decimal, but found '$this'",
            )

    require(intValue > 0) { "Expected $context.$fieldName to be positive, but found '$this'" }

    return intValue
  }

  private fun String.toPatchCodeBlock(): CodeBlock =
      when (this) {
        "" -> CodeBlock.Empty
        else -> CodeBlock.parse(rawContent = this)
      }

  private val software.medusa.flow.core_service.worker.code.CodeFileContent.wholeFileRange:
      LineIndexRange
    get() = LineIndexRange.of(startIndex = LineIndex.First, length = code.lineCount)
}
