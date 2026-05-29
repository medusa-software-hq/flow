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
import software.medusa.commons.unicode.ControlChar

data class MarkdownDocument(
    val chapters: List<MarkdownChapter>,
) {
  fun toMarkdownString(): String = MarkdownRenderer.render(this)

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

private object MarkdownRenderer {
  fun render(document: MarkdownDocument): String =
      document.chapters.joinToString(separator = "\n\n") { chapter ->
        renderChapter(chapter = chapter, level = 1)
      }

  private fun renderChapter(
      chapter: MarkdownChapter,
      level: Int,
  ): String {
    val parts = mutableListOf<String>()

    parts += "${"#".repeat(level)} ${renderInlineContent(chapter.title)}"
    parts += chapter.introBlocks.map { block -> renderBlock(block = block, indent = "") }
    parts +=
        chapter.subChapters.map { subChapter -> renderChapter(chapter = subChapter, level = level + 1) }

    return parts.joinToString(separator = "\n\n")
  }

  private fun renderBlock(
      block: MarkdownBlock,
      indent: String,
  ): String =
      when (block) {
        is MarkdownBlock.Paragraph ->
            renderParagraph(
                inlineContent = block.inlineContent,
                firstLinePrefix = indent,
                continuationPrefix = indent,
            )
        is MarkdownBlock.ListBlock -> renderListBlock(block = block, indent = indent)
        is MarkdownBlock.CodeBlock -> prefixLines(renderFencedCodeBlock(block), indent = indent)
        is MarkdownBlock.RawCodeBlock -> prefixLines(renderRawCodeBlock(block), indent = indent)
      }

  private fun renderListBlock(
      block: MarkdownBlock.ListBlock,
      indent: String,
  ): String =
      block.items.mapIndexed { index, item ->
        renderListItem(
            item = item,
            indent = indent,
            marker = when {
              block.ordered -> "${index + 1}. "
              else -> "- "
            },
        )
      }.joinToString(separator = "\n")

  private fun renderListItem(
      item: MarkdownBlock.ListBlock.Item,
      indent: String,
      marker: String,
  ): String {
    if (item.blocks.isEmpty()) {
      return indent + marker.trimEnd()
    }

    val continuationIndent = indent + " ".repeat(marker.length)
    val parts = mutableListOf<String>()
    val firstBlock = item.blocks.first()

    when (firstBlock) {
      is MarkdownBlock.Paragraph ->
          parts +=
              renderParagraph(
                  inlineContent = firstBlock.inlineContent,
                  firstLinePrefix = indent + marker,
                  continuationPrefix = continuationIndent,
              )
      else -> {
        parts += indent + marker.trimEnd()
        parts += renderBlock(block = firstBlock, indent = continuationIndent)
      }
    }

    parts += item.blocks.drop(1).map { block -> renderBlock(block = block, indent = continuationIndent) }

    return parts.joinToString(separator = "\n\n")
  }

  private fun renderParagraph(
      inlineContent: List<MarkdownInline>,
      firstLinePrefix: String,
      continuationPrefix: String,
  ): String =
      renderInlineContent(inlineContent)
          .split("\n")
          .mapIndexed { index, line ->
            val prefix = when (index) {
              0 -> firstLinePrefix
              else -> continuationPrefix
            }
            prefix + escapeParagraphLineStart(line)
          }
          .joinToString(separator = "\n")

  private fun renderInlineContent(inlineContent: List<MarkdownInline>): String =
      inlineContent.joinToString(separator = "") { inline -> renderInline(inline) }

  private fun renderInline(inline: MarkdownInline): String =
      when (inline) {
        is MarkdownInline.Text -> escapeInlineText(inline.text)
        is MarkdownInline.Code -> renderCodeSpan(inline.code)
        is MarkdownInline.Emphasis -> "*${renderInlineContent(inline.content)}*"
        is MarkdownInline.Strong -> "**${renderInlineContent(inline.content)}**"
        is MarkdownInline.Link -> renderLink(inline)
        MarkdownInline.SoftBreak -> "\n"
        MarkdownInline.HardBreak -> "\\\n"
      }

  private fun renderLink(link: MarkdownInline.Link): String =
      buildString {
        append("[")
        append(renderInlineContent(link.content))
        append("](")
        append("<")
        append(link.destination)
        append(">")

        link.title?.let { title ->
          append(" ")
          append('"')
          append(title.replace("\\", "\\\\").replace("\"", "\\\""))
          append('"')
        }

        append(")")
      }

  private fun renderCodeSpan(code: String): String {
    val fenceLength = maxOf(1, longestRunLength(text = code, char = '`') + 1)
    val fence = "`".repeat(fenceLength)
    val content =
        when {
          code.contains("`") ||
              code.startsWith("`") ||
              code.endsWith("`") ||
              code.startsWith(" ") ||
              code.endsWith(" ") ->
              " $code "
          else -> code
        }

    return "$fence$content$fence"
  }

  private fun renderFencedCodeBlock(block: MarkdownBlock.CodeBlock): String {
    val fence = "`".repeat(maxOf(3, longestRunLength(text = block.code, char = '`') + 1))

    return buildString {
      append(fence)
      block.info?.let(::append)
      append('\n')
      append(block.code)
      if (block.code.isNotEmpty() && !block.code.endsWith("\n")) {
        append('\n')
      }
      append(fence)
    }
  }

  private fun renderRawCodeBlock(block: MarkdownBlock.RawCodeBlock): String =
      buildString {
        append(ControlChar.STX)
        append('\n')
        append(block.code)
        if (block.code.isNotEmpty() && !block.code.endsWith("\n")) {
          append('\n')
        }
        append(ControlChar.ETX)
      }

  private fun prefixLines(
      value: String,
      indent: String,
  ): String = value.split("\n").joinToString(separator = "\n") { line -> indent + line }

  private fun escapeInlineText(text: String): String =
      buildString {
        text.forEach { char ->
          when (char) {
            '\\', '`', '*', '_', '[', ']', '!' -> {
              append('\\')
              append(char)
            }
            else -> append(char)
          }
        }
      }

  private fun escapeParagraphLineStart(line: String): String {
    if (line.isEmpty()) {
      return line
    }

    if (line.startsWith("#") || line.startsWith(">") || line.startsWith("```") || line.startsWith("~~~")) {
      return encodeFirstCharAsEntity(line)
    }

    if (line.startsWith("- ") || line.startsWith("+ ") || line.startsWith("* ")) {
      return encodeFirstCharAsEntity(line)
    }

    val orderedListRegex = Regex("""^(\\d+)([.)])(\\s.*)$""")
    return orderedListRegex.replace(line) { matchResult ->
      encodeFirstCharAsEntity(matchResult.value)
    }
  }

  private fun encodeFirstCharAsEntity(line: String): String {
    val firstChar = line.first()
    return "&#${firstChar.code};" + line.drop(1)
  }

  private fun longestRunLength(
      text: String,
      char: Char,
  ): Int {
    var maxRunLength = 0
    var currentRunLength = 0

    text.forEach { currentChar ->
      if (currentChar == char) {
        currentRunLength += 1
        maxRunLength = maxOf(maxRunLength, currentRunLength)
      } else {
        currentRunLength = 0
      }
    }

    return maxRunLength
  }
}
