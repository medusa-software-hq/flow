package software.medusa.code_agent.virtual_workspace.document

import software.medusa.code_agent.virtual_workspace.document.CodeDocument.FlatDocument
import software.medusa.code_agent.virtual_workspace.document.CodeLanguage.Companion.buildRegionOpeningCommentLine
import software.medusa.code_agent.virtual_workspace.document.CodeLanguage.Companion.regionClosingCommentLine
import software.medusa.commons.code.CodeBlock
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownInline

sealed class CodeNode {
  data class Plain(
      val plainBlock: CodeBlock,
  ) : CodeNode() {
    context(dumpContentContext: CodeDocument.DumpContentContext)
    override fun dumpContent(): CodeBlock = plainBlock

    override fun dumpMemoEntry(): Nothing? = null
  }

  data class Region(
      val title: Title,
      val summary: Summary?,
      val state: State,
      val indentationLevel: CodeBlock.IndentationLevel,
      val innerContainer: Container,
  ) : CodeNode() {
    @JvmInline
    value class Title(
        val text: MarkdownInline.Text,
    )

    @JvmInline
    value class Summary(
        val paragraph: MarkdownBlock.Paragraph,
    )

    enum class State {
      Collapsed,
      Expanded,
    }

    data class Memo(
        val summary: Summary?,
        val state: State,
        val innerContainerMemo: Container.Memo,
    )

    companion object {
      context(restorationContext: CodeDocument.RestorationContext)
      fun restore(
          flatDocument: FlatDocument,
          leadingNode: FlatDocument.Node.RegionStartMarker,
          memo: Memo?,
      ): Region {
        // A start of a new region. We have to forward the cursor.
        restorationContext.forwardCursor()

        val title = leadingNode.title

        val restoredInnerRegion =
            Container.restore(
                flatDocument = flatDocument,
                memo = memo?.innerContainerMemo,
            )

        val trailingNode =
            flatDocument.getNode(
                cursor = restorationContext.forwardCursor(),
            ) as? FlatDocument.Node.RegionEndMarker
                ?: throw IllegalStateException(
                    "Expected region end marker at cursor ${restorationContext.currentCursor.index}"
                )

        val indentationLevel =
            minOf(
                leadingNode.indentationLevel,
                trailingNode.indentationLevel,
            )

        val restoredRegion =
            Region(
                title = title,
                summary = memo?.summary,
                state = memo?.state ?: Region.State.Expanded,
                indentationLevel = indentationLevel,
                innerContainer = restoredInnerRegion,
            )

        return restoredRegion
      }
    }

    context(dumpContentContext: CodeDocument.DumpContentContext)
    override fun dumpContent(): CodeBlock {
      val language = dumpContentContext.language
      val indentedRegionOpeningLine =
          language
              .buildRegionOpeningCommentLine(content = title.text.toMarkdownString())
              .indented(level = indentationLevel)
      val indentedRegionClosingLine =
          language.regionClosingCommentLine.indented(level = indentationLevel)

      return CodeBlock.concat(
          blocks =
              listOf(
                  CodeBlock(lines = listOf(indentedRegionOpeningLine)),
                  innerContainer.dumpContent(),
                  CodeBlock(lines = listOf(indentedRegionClosingLine)),
              ),
      )
    }

    override fun dumpMemoEntry(): Pair<Title, Memo> =
        title to
            Memo(
                summary = summary,
                state = state,
                innerContainerMemo = innerContainer.dumpMemo(),
            )
  }

  @JvmInline
  value class Container(
      val nodes: List<CodeNode>,
  ) {
    data class Memo(
        val subRegionMemoByTitle: Map<Region.Title, Region.Memo>,
    ) {
      fun getSubRegionMemo(title: Region.Title): Region.Memo? = subRegionMemoByTitle[title]
    }

    companion object {
      context(restorationContext: CodeDocument.RestorationContext)
      fun restore(
          flatDocument: FlatDocument,
          memo: Memo?,
      ): Container =
          Container(
              nodes =
                  buildList {
                    while (true) {
                      val nestedNode =
                          restore(
                              flatDocument = flatDocument,
                              parentMemo = memo,
                          ) ?: break

                      add(nestedNode)
                    }
                  },
          )
    }

    context(dumpContentContext: CodeDocument.DumpContentContext)
    fun dumpContent(): CodeBlock =
        CodeBlock.concat(
            blocks = nodes.map { it.dumpContent() },
        )

    fun dumpMemo(): Memo =
        Memo(
            subRegionMemoByTitle = nodes.mapNotNull { it.dumpMemoEntry() }.toMap(),
        )
  }

  companion object {
    context(restorationContext: CodeDocument.RestorationContext)
    fun restore(
        flatDocument: FlatDocument,
        parentMemo: Container.Memo?,
    ): CodeNode? {
      val leadingNode =
          flatDocument.getNode(
              cursor = restorationContext.currentCursor,
          ) ?: return null

      when (leadingNode) {
        is FlatDocument.Node.Plain -> {
          restorationContext.forwardCursor()

          return Plain(plainBlock = leadingNode.block)
        }

        is FlatDocument.Node.RegionStartMarker -> {
          val regionMemo = parentMemo?.getSubRegionMemo(title = leadingNode.title)

          return Region.restore(
              flatDocument = flatDocument,
              leadingNode = leadingNode,
              memo = regionMemo,
          )
        }

        is FlatDocument.Node.RegionEndMarker -> {
          // Possibly an end of an open region. We shouldn't forward the cursor.
          return null
        }
      }
    }
  }

  context(dumpContentContext: CodeDocument.DumpContentContext)
  abstract fun dumpContent(): CodeBlock

  abstract fun dumpMemoEntry(): Pair<Region.Title, Region.Memo>?
}
