package software.medusa.commons.code

import software.medusa.commons.unicode.ControlChar

/**
 * A multi-line block of a code. An empty code block (containing no lines) is possible. Code must
 * consist solely of safe characters. _Safe_ characters consist of: non-control characters,
 * horizontal tab (\t) and line feed (\n).
 */
data class CodeBlock(
    val lines: List<Line>,
) {
  class IllegalCodeContentException(illegalChar: Char) :
      IllegalArgumentException(
          "Code content cannot contain control characters, but found: ${illegalChar.toUPlusString()}",
      )

  @JvmInline
  value class LineIndex(
      /** Zero-based line index. */
      val indexZeroBased: Int,
  ) : Comparable<LineIndex> {
    companion object {
      val First = LineIndex(indexZeroBased = 0)

      fun ofOneBased(
          indexOneBased: Int,
      ): LineIndex {
        require(indexOneBased > 0) { "One-based line index must be positive" }

        return LineIndex(indexZeroBased = indexOneBased - 1)
      }
    }

    init {
      require(indexZeroBased >= 0) { "Line index must be non-negative" }
    }

    override fun compareTo(other: LineIndex): Int =
        compareValuesBy(this, other) { it.indexZeroBased }

    val next: LineIndex
      get() = LineIndex(indexZeroBased = indexZeroBased + 1)

    val indexOneBased: Int
      get() = indexZeroBased + 1
  }

  /**
   * Represents a range of line indices in a code file. An empty line range is possible and
   * describes the "empty space" before/after a line.
   */
  data class LineIndexRange(
      /** Start line index (inclusive). */
      val startIndex: LineIndex,
      /** End line index (exclusive). */
      val endIndexExclusive: LineIndex,
  ) {
    companion object {
      /**
       * Creates an empty line index range at the [startIndex]. The resulting range describes the
       * "empty space" right before the line at [startIndex] or the "empty space" adjacent to the
       * end of the file if [startIndex] is equal to the line count of the file.
       */
      fun empty(
          startIndex: LineIndex,
      ): LineIndexRange =
          LineIndexRange(
              startIndex = startIndex,
              endIndexExclusive = startIndex,
          )

      fun of(
          startIndex: LineIndex,
          length: Int,
      ): LineIndexRange {
        require(length >= 0) { "Line index range length must be non-negative" }

        return LineIndexRange(
            startIndex = startIndex,
            endIndexExclusive =
                LineIndex(
                    indexZeroBased = startIndex.indexZeroBased + length,
                ),
        )
      }
    }

    init {
      require(startIndex <= endIndexExclusive) {
        "Start line index must be less than or equal to end line index"
      }
    }

    /**
     * Checks whether this line index range collides with another line index range. Two line index
     * ranges collide if they share at least one line index or if the end index of one range is
     * equal to the start index of the other range (i.e., they are adjacent).
     */
    fun collides(
        other: LineIndexRange,
    ): Boolean = startIndex <= other.endIndexExclusive && endIndexExclusive >= other.startIndex

    /**
     * Checks whether this line index range overlaps with another line index range. Two line index
     * ranges overlap if they share at least one line index.
     */
    fun overlaps(
        other: LineIndexRange,
    ): Boolean = startIndex < other.endIndexExclusive && endIndexExclusive > other.startIndex
  }

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

      /** Checks whether a character is safe for inclusion in line content. */
      fun isSafe(char: Char): Boolean = !ControlChar.isControl(char) || char == '\t'

      /**
       * @throws IllegalCodeContentException if [rawLineContent] contains any control characters.
       */
      fun parse(
          rawLineContent: String,
      ): Line {
        rawLineContent.forEach {
          if (!Line.isSafe(it)) {
            throw IllegalCodeContentException(illegalChar = it)
          }
        }

        return Line(content = rawLineContent)
      }
    }

    init {
      require(content.none { ControlChar.isControl(it) }) {
        "Line content cannot contain control characters (including newline)"
      }
    }
  }

  companion object {
    /** A code block with no lines. */
    val Empty = CodeBlock(lines = emptyList())

    /** A code block consisting of a single empty line. */
    val SingleEmptyLine = CodeBlock(lines = listOf(Line.Empty))

    /** Checks whether a character is safe for inclusion in code content. */
    fun isSafe(char: Char): Boolean = Line.isSafe(char) || char == '\n'

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
     * [rawContent] is expected to consist of LF-terminated lines. If [rawContent] lacks a trailing
     * LF character, it will be parsed as a one-line file.
     *
     * @throws IllegalCodeContentException if [rawContent] contains any control characters.
     */
    fun parse(
        rawContent: String,
    ): CodeBlock {
      val strippedRawContent =
          when {
            rawContent.endsWith('\n') -> rawContent.dropLast(1)
            else -> rawContent
          }

      val lines = strippedRawContent.split('\n').map { Line.parse(rawLineContent = it) }

      return CodeBlock(lines = lines)
    }
  }

  val height: Int
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

  /**
   * Applies a [CodePatch] to this code block and returns the resulting code block. The
   * [baseLineIndex] parameter specifies the line index in the original code file that corresponds
   * to the first line of this code block.
   */
  fun applyPatch(
      patch: CodePatch,
      baseLineIndex: LineIndex = LineIndex.First,
  ): CodeBlock {
    TODO()
  }
}

private fun Char.toUPlusString(): String = "U+" + code.toString(16).uppercase().padStart(4, '0')
