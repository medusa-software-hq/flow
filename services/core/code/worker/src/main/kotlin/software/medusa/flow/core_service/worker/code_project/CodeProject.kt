package software.medusa.flow.core_service.worker.code_project

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.LineIndex
import software.medusa.flow.core_service.worker.code_project.CodeProject.BulkCodeFileContent
import software.medusa.git.UnixPath

interface CodeProject {
  @JvmInline
  value class ModuleLocator(
      val modulePath: UnixPath.Relative,
  )

  /** A multi-line block of a code. An empty code block (containing no lines) is possible. */
  data class CodeBlock(
      val lines: List<Line>,
  ) {
    data class IndexedLine(
        val index: LineIndex,
        val line: Line,
    )

    /** A single line of code. */
    @JvmInline
    value class Line(
        val content: String,
    ) {
      companion object {
        /** A line with no characters. */
        val Empty = Line(content = "")
      }

      init {
        require(!content.contains('\n')) { "Line content cannot contain newline characters" }
      }
    }

    companion object {
      /** A code block with no lines. */
      val Empty = CodeBlock(lines = emptyList())

      /** A code block consisting of a single empty line. */
      val SingleEmptyLine = CodeBlock(lines = listOf(Line.Empty))

      fun of(
          vararg lines: String,
      ): CodeBlock = of(lines = lines.toList())

      fun of(
          lines: List<String>,
      ): CodeBlock =
          CodeBlock(
              lines = lines.map { Line(content = it) },
          )

      fun concat(
          vararg blocks: CodeBlock,
      ): CodeBlock = concat(blocks = blocks.toList())

      fun concat(
          blocks: List<CodeBlock>,
      ): CodeBlock =
          CodeBlock(
              lines = blocks.flatMap { it.lines },
          )

      fun joinBy(
          blocks: List<CodeBlock>,
          separator: CodeBlock,
      ): CodeBlock =
          when (blocks.size) {
            0 -> Empty
            1 -> blocks.single()
            else -> {
              val lines = buildList {
                for ((index, block) in blocks.withIndex()) {
                  addAll(block.lines)

                  if (index < blocks.size - 1) {
                    addAll(separator.lines)
                  }
                }
              }

              CodeBlock(lines = lines)
            }
          }

      /**
       * Parses the raw content of a code block into a [CodeBlock] by splitting it into lines.
       * [rawContent] is expected to consist of LF-terminated lines. If [rawContent] lacks a
       * trailing LF character, it will be parsed as a one-line file.
       */
      fun parse(
          rawContent: String,
      ): CodeBlock {
        val strippedRawContent =
            when {
              rawContent.endsWith('\n') -> rawContent.dropLast(1)
              else -> rawContent
            }

        val lines = strippedRawContent.split('\n').map { Line(content = it) }

        return CodeBlock(lines = lines)
      }
    }

    val lineCount: Int
      get() = lines.size

    /** Dumps the content of the code block as a string with LF-terminated lines. */
    fun dump(): String = lines.joinToString("") { "${it.content}\n" }

    fun buildIndexedLines(
        baseIndex: LineIndex,
    ): Sequence<IndexedLine> =
        lines.asSequence().mapIndexed { indexZeroBased, line ->
          IndexedLine(
              index =
                  LineIndex(
                      indexZeroBased = baseIndex.indexZeroBased + indexZeroBased,
                  ),
              line = line,
          )
        }
  }

  /** Content of a code file. */
  @JvmInline
  value class CodeFileContent(
      /** A single block containing the whole file content. */
      val code: CodeBlock,
  ) {
    companion object {
      /** A code file with no content (i.e. an empty file). */
      val Empty = CodeFileContent(code = CodeBlock.Empty)

      fun parse(
          rawContent: String,
      ): CodeFileContent =
          CodeFileContent(
              code = CodeBlock.parse(rawContent = rawContent),
          )
    }

    /** Dumps the content of the code file as a string with LF-terminated lines. */
    fun dump(): String = code.dump()

    fun applyPatch(
        patch: AiCodeEditor.Patch,
    ): CodeFileContent {
      val oldLines = code.lines

      val patchesInOrder =
          patch.newCodeBlockByOldLineIndexRange.entries.sortedBy { (lineIndexRange, _) ->
            lineIndexRange.startIndex.indexZeroBased
          }

      val newLines = buildList {
        var nextOldLineIndex = 0

        for ((oldLineIndexRange, newCodeBlock) in patchesInOrder) {
          val startIndex = oldLineIndexRange.startIndex.indexZeroBased
          val endIndexExclusive = oldLineIndexRange.endIndexExclusive.indexZeroBased

          require(startIndex <= oldLines.size) {
            "Patch start index $startIndex is out of bounds for file with ${oldLines.size} lines"
          }

          require(endIndexExclusive <= oldLines.size) {
            "Patch end index $endIndexExclusive is out of bounds for file with ${oldLines.size} lines"
          }

          addAll(oldLines.subList(nextOldLineIndex, startIndex))

          val adjustedNewCodeBlockLines =
              when {
                startIndex < endIndexExclusive -> {
                  val anchorIndent =
                      oldLines[startIndex].content.takeWhile { it == ' ' || it == '\t' }
                  val patchBaselineIndent =
                      newCodeBlock.lines
                          .firstOrNull { it.content.isNotEmpty() }
                          ?.content
                          ?.takeWhile { it == ' ' || it == '\t' } ?: ""

                  newCodeBlock.lines.map { line ->
                    when (line.content) {
                      "" -> line
                      else -> {
                        val contentWithoutBaselineIndent =
                            when {
                              line.content.startsWith(patchBaselineIndent) -> {
                                line.content.removePrefix(patchBaselineIndent)
                              }

                              else -> line.content
                            }

                        CodeBlock.Line(content = anchorIndent + contentWithoutBaselineIndent)
                      }
                    }
                  }
                }

                else -> newCodeBlock.lines
              }

          addAll(adjustedNewCodeBlockLines)

          nextOldLineIndex = endIndexExclusive
        }

        addAll(oldLines.subList(nextOldLineIndex, oldLines.size))
      }

      return CodeFileContent(
          code = CodeBlock(lines = newLines),
      )
    }
  }

  @JvmInline
  value class BulkCodeFileContent(
      val codeFileContentByPath: Map<UnixPath.Relative, CodeFileContent>,
  )

  sealed class FormattingResult {
    data class FoundSyntaxErrors(
        val formatterOutput: String,
    ) : FormattingResult()

    data object Formatted : FormattingResult()
  }

  sealed class AnalysisResult {
    data class Rejected(
        val analyzerOutput: String,
    ) : AnalysisResult()

    data object Accepted : AnalysisResult()
  }

  sealed class TestingResult {
    data class SomeFailed(
        val testingOutput: String,
    ) : TestingResult()

    data object AllPassed : TestingResult()
  }

  suspend fun readFile(
      filePath: UnixPath.Relative,
  ): CodeFileContent

  suspend fun writeFile(
      filePath: UnixPath.Relative,
      newFileContent: CodeFileContent,
  )

  suspend fun format(): FormattingResult

  suspend fun analyze(): AnalysisResult

  suspend fun test(): TestingResult
}

suspend fun CodeProject.readFilesBulk(
    filePaths: Set<UnixPath.Relative>,
): BulkCodeFileContent {
  TODO()
}

suspend fun CodeProject.writeFilesBulk(
    newBulkCodeFileContent: BulkCodeFileContent,
) {
  TODO()
}
