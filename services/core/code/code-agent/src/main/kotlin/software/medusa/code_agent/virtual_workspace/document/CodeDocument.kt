package software.medusa.code_agent.virtual_workspace.document

import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodePatch
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.markdown.MarkdownInline

data class CodeDocument(
    val language: CodeLanguage,
    val rootContainer: CodeNode.Container,
) {
  data class FlatDocument(
      val nodes: List<Node>,
  ) {
    sealed class Node {
      data class Plain(
          val block: CodeBlock,
      ) : Node()

      sealed class RegionEdgeMarker : Node() {
        abstract val indentationLevel: CodeBlock.IndentationLevel
      }

      data class RegionStartMarker(
          val title: CodeNode.Region.Title,
          override val indentationLevel: CodeBlock.IndentationLevel,
      ) : RegionEdgeMarker()

      data class RegionEndMarker(
          override val indentationLevel: CodeBlock.IndentationLevel,
      ) : RegionEdgeMarker()
    }

    // TODO: Base on LineIndex. Or maybe let's use LineIndex directly?
    @JvmInline
    value class Cursor(
        val index: Int,
    ) {
      companion object {
        val Initial = Cursor(index = 0)
      }

      fun skip(): Cursor = Cursor(index = index + 1)
    }

    fun getNode(
        cursor: Cursor,
    ): Node? = nodes.getOrNull(cursor.index)

    companion object {
      fun parse(
          language: CodeLanguage,
          block: CodeBlock,
      ): FlatDocument {
        val escapedLineCommentPrefix = Regex.escape(language.lineCommentPrefix)
        val regionStartRegex =
            Regex(
                "^(\\s*)$escapedLineCommentPrefix${Regex.escape(CodeLanguage.regionOpeningMarker)}(?:\\s+(.*\\S))?\\s*$"
            )
        val regionEndRegex =
            Regex(
                "^(\\s*)$escapedLineCommentPrefix${Regex.escape(CodeLanguage.regionClosingMarker)}\\s*$"
            )

        val nodes = mutableListOf<Node>()
        val plainLines = mutableListOf<CodeBlock.Line>()

        fun flushPlainLines() {
          if (plainLines.isEmpty()) {
            return
          }

          nodes += Node.Plain(block = CodeBlock(lines = plainLines.toList()))
          plainLines.clear()
        }

        for ((lineIndex, line) in block.buildIndexedLines(baseIndex = CodeBlock.LineIndex.First)) {
          val regionStartMatch = regionStartRegex.matchEntire(line.content)

          if (regionStartMatch != null) {
            flushPlainLines()

            val (indentationText, rawTitle) = regionStartMatch.destructured
            val titleText = rawTitle.trim()

            require(titleText.isNotEmpty()) {
              "Region opening marker at line ${lineIndex.indexOneBased} must include a non-blank title"
            }

            nodes +=
                Node.RegionStartMarker(
                    title = CodeNode.Region.Title(text = MarkdownInline.Text(titleText)),
                    indentationLevel = CodeBlock.IndentationLevel(indentationText.length),
                )
            continue
          }

          if (regionEndRegex.matches(line.content)) {
            flushPlainLines()

            val indentationText = regionEndRegex.matchEntire(line.content)!!.groupValues[1]

            nodes +=
                Node.RegionEndMarker(
                    indentationLevel = CodeBlock.IndentationLevel(indentationText.length),
                )
            continue
          }

          plainLines += line
        }

        flushPlainLines()

        return FlatDocument(nodes = nodes)
      }
    }
  }

  @JvmInline
  value class Memo(
      val rootContainerMemo: CodeNode.Container.Memo,
  )

  data class DumpContentContext(
      val language: CodeLanguage,
  )

  class RestorationContext {
    private var cursor: FlatDocument.Cursor = FlatDocument.Cursor.Initial

    val currentCursor: FlatDocument.Cursor
      get() = cursor

    /**
     * Forwards cursor to the next line.
     *
     * @return The old cursor before forwarding.
     */
    fun forwardCursor(): FlatDocument.Cursor {
      val oldCursor = cursor

      cursor = oldCursor.skip()

      return oldCursor
    }
  }

  companion object {
    /** Loads a fully-expanded document without region summaries. */
    fun load(
        language: CodeLanguage,
        content: TechFileContent.Code,
    ): CodeDocument =
        restore(
            language = language,
            content = content,
            memo = null,
        )

    /** Restores the document, preserving previous expansion state and region summaries. */
    fun restore(
        language: CodeLanguage,
        content: TechFileContent.Code,
        memo: Memo?,
    ): CodeDocument {
      val flatDocument =
          FlatDocument.parse(
              language = language,
              block = content.code,
          )

      val restorationContext = RestorationContext()

      val restoredRootContainer =
          with(restorationContext) {
            CodeNode.Container.restore(
                flatDocument = flatDocument,
                memo = memo?.rootContainerMemo,
            )
          }

      val trailingNode = flatDocument.getNode(restorationContext.currentCursor)

      require(restorationContext.currentCursor.index == flatDocument.nodes.size) {
        when (trailingNode) {
          is FlatDocument.Node.RegionEndMarker ->
              "Unexpected unmatched region end marker in document"
          else ->
              "Unexpected trailing node at cursor ${restorationContext.currentCursor.index}: $trailingNode"
        }
      }

      return CodeDocument(
          language = language,
          rootContainer = restoredRootContainer,
      )
    }
  }

  context(dumpContentContext: DumpContentContext)
  fun dumpContent(): TechFileContent.Code =
      TechFileContent.Code(
          code = rootContainer.dumpContent(),
      )

  fun dumpMemo(): Memo =
      Memo(
          rootContainerMemo = rootContainer.dumpMemo(),
      )

  /**
   * Applies [patch] to this document.
   *
   * If the patch introduces new regions, they will not have filled summaries.
   *
   * @return The patched document.
   */
  fun applyPatch(
      patch: CodePatch,
  ): CodeDocument {
    val oldContent =
        with(
            DumpContentContext(language = language),
        ) {
          dumpContent()
        }

    val memo = dumpMemo()

    val newContent = oldContent.applyPatch(patch = patch)

    val restoredDocument =
        restore(
            language = language,
            content = newContent,
            memo = memo,
        )

    return restoredDocument
  }
}
