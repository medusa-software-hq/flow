package software.medusa.markdown

import org.commonmark.ext.cc.CcCodeBlock
import org.commonmark.ext.cc.CcExtension
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.parser.Parser

data class MarkdownDocument(
    val chapters: List<MarkdownChapter>,
) {
  companion object {
    fun parse(markdown: String): MarkdownDocument = MarkdownParser.parse(markdown)
  }
}

data class MarkdownChapter(
    val title: List<MarkdownInline>,
    val introBlocks: List<MarkdownBlock>,
    val subChapters: List<MarkdownChapter>,
)

sealed class MarkdownBlock {
  data class Paragraph(
      val inlineContent: List<MarkdownInline>,
  ) : MarkdownBlock()

  data class ListBlock(
      val ordered: Boolean,
      val items: kotlin.collections.List<Item>,
  ) : MarkdownBlock() {
    data class Item(
        val blocks: kotlin.collections.List<MarkdownBlock>,
    )
  }

  data class CodeBlock(
      val code: String,
      val info: String?,
  ) : MarkdownBlock()

  data class RawCodeBlock(
      val code: String,
  ) : MarkdownBlock()
}

sealed class MarkdownInline {
  data class Text(
      val text: String,
  ) : MarkdownInline()

  data class Code(
      val code: String,
  ) : MarkdownInline()

  data class Emphasis(
      val content: List<MarkdownInline>,
  ) : MarkdownInline()

  data class Strong(
      val content: List<MarkdownInline>,
  ) : MarkdownInline()

  data class Link(
      val destination: String,
      val title: String?,
      val content: List<MarkdownInline>,
  ) : MarkdownInline()

  data object SoftBreak : MarkdownInline()

  data object HardBreak : MarkdownInline()
}

class MarkdownParseException(
    message: String,
) : IllegalArgumentException(message)

private object MarkdownParser {
  private val parser = Parser.builder().extensions(listOf(CcExtension.create())).build()

  fun parse(markdown: String): MarkdownDocument {
    val document = parser.parse(markdown)

    requireSupportedContainer(document)

    return MarkdownDocument(
        chapters = parseChapterSequence(nodes = document.childNodes(), expectedLevel = 1),
    )
  }

  private fun parseChapterSequence(
      nodes: List<Node>,
      expectedLevel: Int,
  ): List<MarkdownChapter> {
    if (nodes.isEmpty()) {
      return emptyList()
    }

    val chapters = mutableListOf<MarkdownChapter>()
    var index = 0

    while (index < nodes.size) {
      val heading = nodes[index] as? Heading ?: unsupported(nodes[index], "Expected ATX heading")

      if (heading.level != expectedLevel) {
        throw MarkdownParseException(
            "Expected heading level $expectedLevel, but found level ${heading.level}",
        )
      }

      index += 1
      val introBlocks = mutableListOf<MarkdownBlock>()
      val subChapterNodes = mutableListOf<Node>()

      while (index < nodes.size) {
        val node = nodes[index]

        when (node) {
          is Heading -> {
            when {
              node.level == expectedLevel -> break
              node.level == expectedLevel + 1 -> {
                subChapterNodes += node
                index += 1

                while (index < nodes.size) {
                  val nestedNode = nodes[index]
                  if (nestedNode is Heading && nestedNode.level <= expectedLevel) {
                    break
                  }
                  subChapterNodes += nestedNode
                  index += 1
                }
              }
              else -> {
                throw MarkdownParseException(
                    "Invalid heading level ${node.level}; expected $expectedLevel or ${expectedLevel + 1}",
                )
              }
            }
          }
          else -> {
            ensureNoSubChaptersYet(subChapterNodes = subChapterNodes)
            introBlocks += parseBlock(node)
            index += 1
          }
        }
      }

      chapters +=
          MarkdownChapter(
              title = parseInlineNodes(heading.childNodes()),
              introBlocks = introBlocks,
              subChapters =
                  parseChapterSequence(nodes = subChapterNodes, expectedLevel = expectedLevel + 1),
          )
    }

    return chapters
  }

  private fun ensureNoSubChaptersYet(subChapterNodes: List<Node>) {
    if (subChapterNodes.isNotEmpty()) {
      throw MarkdownParseException("Chapter intro blocks must appear before sub-chapters")
    }
  }

  private fun parseBlock(node: Node): MarkdownBlock =
      when (node) {
        is Paragraph -> MarkdownBlock.Paragraph(inlineContent = parseInlineNodes(node.childNodes()))
        is ListBlock ->
            MarkdownBlock.ListBlock(
                ordered = node is OrderedList,
                items = node.childNodes().map(::parseListItem),
            )
        is FencedCodeBlock ->
            MarkdownBlock.CodeBlock(
                code = node.literal,
                info = node.info.takeUnless(String::isBlank),
            )
        is IndentedCodeBlock -> MarkdownBlock.CodeBlock(code = node.literal, info = null)
        is CcCodeBlock -> MarkdownBlock.RawCodeBlock(code = node.literal)
        else -> unsupported(node, "Unsupported block node")
      }

  private fun parseListItem(node: Node): MarkdownBlock.ListBlock.Item {
    val item = node as? ListItem ?: unsupported(node, "Expected list item")

    return MarkdownBlock.ListBlock.Item(
        blocks = item.childNodes().map(::parseBlock),
    )
  }

  private fun parseInlineNodes(nodes: List<Node>): List<MarkdownInline> = nodes.map(::parseInline)

  private fun parseInline(node: Node): MarkdownInline =
      when (node) {
        is Text -> MarkdownInline.Text(node.literal)
        is Code -> MarkdownInline.Code(node.literal)
        is Emphasis -> MarkdownInline.Emphasis(parseInlineNodes(node.childNodes()))
        is StrongEmphasis -> MarkdownInline.Strong(parseInlineNodes(node.childNodes()))
        is Link ->
            MarkdownInline.Link(
                destination = node.destination,
                title = node.title,
                content = parseInlineNodes(node.childNodes()),
            )
        is SoftLineBreak -> MarkdownInline.SoftBreak
        is HardLineBreak -> MarkdownInline.HardBreak
        else -> unsupported(node, "Unsupported inline node")
      }

  private fun requireSupportedContainer(node: Node) {
    for (child in node.childNodes()) {
      when (child) {
        is Heading,
        is Paragraph,
        is ListBlock,
        is FencedCodeBlock,
        is IndentedCodeBlock,
        is CcCodeBlock,
        -> Unit
        else -> unsupported(child, "Unsupported top-level node")
      }
    }
  }

  private fun unsupported(
      node: Node,
      description: String,
  ): Nothing {
    throw MarkdownParseException("$description: ${node::class.simpleName}")
  }

  private fun Node.childNodes(): List<Node> {
    val children = mutableListOf<Node>()
    var child = firstChild

    while (child != null) {
      children += child
      child = child.next
    }

    return children
  }
}
