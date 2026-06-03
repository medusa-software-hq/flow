package software.medusa.markdown

import org.commonmark.ext.cc.CcCodeBlock
import org.commonmark.ext.cc.CcExtension
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
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
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.markdown.MarkdownNodeRendererContext
import org.commonmark.renderer.markdown.MarkdownNodeRendererFactory
import org.commonmark.renderer.markdown.MarkdownRenderer
import org.commonmark.renderer.markdown.MarkdownWriter
import software.medusa.commons.unicode.ControlChar

data class MarkdownDocument(
    val chapters: List<MarkdownChapter>,
) {
  companion object {
    fun parse(markdown: String): MarkdownDocument = MarkdownCommonMark.parse(markdown)
  }

  fun toMarkdownString(): String = MarkdownCommonMark.render(this)
}

data class MarkdownChapter(
    val title: List<MarkdownInline>,
    val blocks: List<MarkdownBlock>,
    val subChapters: List<MarkdownChapter>,
) {
  companion object {
    fun wrapper(
        title: List<MarkdownInline>,
        introBlocks: List<MarkdownBlock> = emptyList(),
        subChapters: List<MarkdownChapter>,
    ): MarkdownChapter =
        MarkdownChapter(
            title = title,
            blocks = introBlocks,
            subChapters = subChapters,
        )

    fun leaf(
        title: List<MarkdownInline>,
        blocks: List<MarkdownBlock>,
    ): MarkdownChapter =
        wrapper(
            title = title,
            introBlocks = blocks,
            subChapters = emptyList(),
        )
  }
}

sealed class MarkdownBlock {
  data class Paragraph(
      val inlineContent: List<MarkdownInline>,
  ) : MarkdownBlock()

  data class ListBlock(
      val ordered: Boolean = false,
      val items: kotlin.collections.List<Item>,
  ) : MarkdownBlock() {
    data class Item(
        val blocks: kotlin.collections.List<MarkdownBlock>,
    ) {
      companion object {
        fun inline(
            inlineContent: List<MarkdownInline>,
        ): Item =
            Item(
                blocks = listOf(Paragraph(inlineContent)),
            )

        fun inline(
            vararg inlineContent: MarkdownInline,
        ): Item = inline(inlineContent.toList())
      }
    }
  }

  data class CodeBlock(
      val code: String,
      val info: String? = null,
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

  fun toMarkdownString(): String = MarkdownCommonMark.render(this)
}

class MarkdownParseException(
    message: String,
) : IllegalArgumentException(message)

private object MarkdownCommonMark {
  private val parser = Parser.builder().extensions(listOf(CcExtension.create())).build()
  private val renderer =
      MarkdownRenderer.builder().nodeRendererFactory(CcMarkdownNodeRendererFactory).build()

  fun parse(markdown: String): MarkdownDocument {
    val document = parser.parse(markdown)

    requireSupportedContainer(document)

    return MarkdownDocument(
        chapters = parseChapterSequence(nodes = document.childNodes(), expectedLevel = 1),
    )
  }

  fun render(document: MarkdownDocument): String = renderer.render(document.toCommonMarkDocument())

  fun render(inline: MarkdownInline): String =
      renderer
          .render(
              Paragraph().also { paragraph -> paragraph.appendChild(inline.toCommonMarkNode()) },
          )
          .trimEnd('\n')

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
              blocks = introBlocks,
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

  private fun parseInlineNodes(nodes: List<Node>): List<MarkdownInline> = nodes.mapNotNull { node ->
    when (node) {
      is Text if node.literal.isEmpty() -> null
      else -> parseInline(node)
    }
  }

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

  private fun MarkdownDocument.toCommonMarkDocument(): Document =
      Document().also { document ->
        chapters
            .flatMap { chapter -> chapter.toCommonMarkNodes(level = 1) }
            .forEach(document::appendChild)
      }

  private fun MarkdownChapter.toCommonMarkNodes(level: Int): List<Node> = buildList {
    add(
        Heading().also { heading ->
          heading.level = level
          title.toCommonMarkChildren().forEach(heading::appendChild)
        },
    )
    addAll(blocks.map { block -> block.toCommonMarkNode() })
    subChapters.forEach { subChapter -> addAll(subChapter.toCommonMarkNodes(level = level + 1)) }
  }

  private fun MarkdownBlock.toCommonMarkNode(): Node =
      when (this) {
        is MarkdownBlock.Paragraph ->
            Paragraph().also { paragraph ->
              inlineContent.toCommonMarkChildren().forEach(paragraph::appendChild)
            }
        is MarkdownBlock.ListBlock ->
            when {
              ordered ->
                  OrderedList().also(::configureList).also { list ->
                    items.forEach { item -> list.appendChild(item.toCommonMarkNode()) }
                  }
              else ->
                  BulletList().also(::configureList).also { list ->
                    items.forEach { item -> list.appendChild(item.toCommonMarkNode()) }
                  }
            }
        is MarkdownBlock.CodeBlock ->
            FencedCodeBlock().also { codeBlock ->
              codeBlock.literal = code
              codeBlock.info = info
            }
        is MarkdownBlock.RawCodeBlock ->
            CcCodeBlock().also { codeBlock -> codeBlock.literal = code }
      }

  private fun MarkdownBlock.ListBlock.Item.toCommonMarkNode(): ListItem =
      ListItem().also { item ->
        blocks
            .map { block -> block.toCommonMarkNode() }
            .forEach { blockNode -> item.appendChild(blockNode) }
      }

  private fun List<MarkdownInline>.toCommonMarkChildren(): List<Node> = map { inline ->
    inline.toCommonMarkNode()
  }

  private fun MarkdownInline.toCommonMarkNode(): Node =
      when (this) {
        is MarkdownInline.Text -> Text(text)
        is MarkdownInline.Code -> Code(code)
        is MarkdownInline.Emphasis ->
            Emphasis().also { emphasis ->
              content.toCommonMarkChildren().forEach(emphasis::appendChild)
            }
        is MarkdownInline.Strong ->
            StrongEmphasis().also { strong ->
              content.toCommonMarkChildren().forEach(strong::appendChild)
            }
        is MarkdownInline.Link ->
            Link(destination, title).also { link ->
              content.toCommonMarkChildren().forEach(link::appendChild)
            }
        MarkdownInline.SoftBreak -> SoftLineBreak()
        MarkdownInline.HardBreak -> HardLineBreak()
      }

  private fun configureList(list: org.commonmark.node.ListBlock) {
    list.isTight = false
    when (list) {
      is BulletList -> list.marker = "-"
      is OrderedList -> {
        list.markerStartNumber = 1
        list.markerDelimiter = "."
      }
    }
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

private object CcMarkdownNodeRendererFactory : MarkdownNodeRendererFactory {
  override fun create(context: MarkdownNodeRendererContext): NodeRenderer =
      CcMarkdownNodeRenderer(context)

  override fun getSpecialCharacters(): MutableSet<Char> = mutableSetOf()
}

private class CcMarkdownNodeRenderer(
    private val context: MarkdownNodeRendererContext,
) : NodeRenderer {
  private val writer: MarkdownWriter = context.writer

  override fun getNodeTypes(): Set<Class<out Node>> = setOf(CcCodeBlock::class.java)

  override fun render(node: Node) {
    val ccCodeBlock =
        node as? CcCodeBlock ?: error("Unexpected node type: ${node::class.simpleName}")
    val lines = ccCodeBlock.literal.split("\n").dropLastWhile(String::isEmpty)

    writer.raw(ControlChar.STX.toString())
    writer.line()
    lines.forEach { line ->
      writer.raw(line)
      writer.line()
    }
    writer.raw(ControlChar.ETX.toString())
    writer.block()
  }
}
