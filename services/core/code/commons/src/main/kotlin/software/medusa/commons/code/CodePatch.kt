package software.medusa.commons.code

/**
 * A patch for a [CodeBlock].
 *
 * The line range keys refer to the original code. The patch fragments must not overlap or be
 * adjacent. If the fragments were to be adjacent, they should be merged into a single fragment
 * instead. The patch must contain at least one fragment.
 *
 * Should not be a no-op, i.e., the patched code is expected to be different from the original code.
 * This is not verified.
 */
data class CodePatch(
    /**
     * A mapping from line index ranges in the original code to a patch fragment (possibly empty).
     * The line index ranges must not overlap or be adjacent.
     */
    val fragmentByOldLineIndexRange: Map<CodeBlock.LineIndexRange, Fragment>,
) {
  /**
   * A fragment of a code patch, which describes how to modify a specific line index range in the
   * original code.
   */
  @JvmInline
  value class Fragment(
      /**
       * The new content that should replace the code in the corresponding line index range in the
       * original code. This can be empty, which indicates that the code in the corresponding line
       * range should be deleted.
       */
      val newContent: CodeBlock,
  ) {
    companion object {
      val Empty = Fragment(newContent = CodeBlock.Empty)
    }
  }

  init {
    require(fragmentByOldLineIndexRange.isNotEmpty()) {
      "A code patch must contain at least one fragment"
    }

    val isStructuredCorrectly =
        fragmentByOldLineIndexRange.entries
            .sortedBy { it.key.startIndex }
            .zipWithNext()
            .none { (prevEntry, nextEntry) ->
              val prevLineRange = prevEntry.key
              val nextLineRange = nextEntry.key

              prevLineRange.collides(nextLineRange)
            }

    require(isStructuredCorrectly) {
      "Line index ranges in the patch set must not collide (i.e., they must not overlap or be adjacent)"
    }
  }

  companion object {
    fun merge(
        patches: Iterable<CodePatch>,
    ): CodePatch =
        CodePatch(
            fragmentByOldLineIndexRange =
                patches
                    .flatMap { patch ->
                      patch.fragmentByOldLineIndexRange.map { entry -> entry.toPair() }
                    }
                    .toMap(),
        )
  }

  /**
   * The full line index range covered by this patch, which is the smallest line index range that
   * covers all line index ranges in [fragmentByOldLineIndexRange].
   */
  val spanLineIndexRange: CodeBlock.LineIndexRange by lazy {
    val minLineIndex = fragmentByOldLineIndexRange.keys.minOf { it.startIndex }

    val maxLineIndexExclusive = fragmentByOldLineIndexRange.keys.maxOf { it.endIndexExclusive }

    CodeBlock.LineIndexRange(
        startIndex = minLineIndex,
        endIndexExclusive = maxLineIndexExclusive,
    )
  }
}
