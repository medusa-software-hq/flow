package software.medusa.code_agent.exploration

import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline

data object MarkdownEncodingUtils {
  class IllegalMarkdownEncodingException(message: String) : RuntimeException(message)

  data class KeyValueMap(
      val entries: List<Entry>,
  ) {
    data class Entry(
        val key: String,
        val node: Node,
    )

    sealed interface Node {
      fun encodeToListItem(key: String): MarkdownBlock.ListBlock.Item {
        val keyPrefix = "$key:"

        return when (this) {
          is InlineValue ->
              MarkdownBlock.ListBlock.Item(
                  blocks =
                      listOf(
                          MarkdownBlock.Paragraph(
                              inlineContent =
                                  buildList {
                                    add(MarkdownInline.Text(keyPrefix))

                                    if (inlineContent.isNotEmpty()) {
                                      add(MarkdownInline.Text(" "))
                                      addAll(inlineContent)
                                    }
                                  },
                          ),
                      ),
              )

          is Group ->
              MarkdownBlock.ListBlock.Item(
                  blocks =
                      listOf(
                          MarkdownBlock.Paragraph(
                              inlineContent = listOf(MarkdownInline.Text(keyPrefix)),
                          ),
                          map.encodeToListBlock(),
                      ),
              )
        }
      }
    }

    data class InlineValue(
        val inlineContent: List<MarkdownInline>,
    ) : Node

    data class Group(
        val map: KeyValueMap,
    ) : Node {
      constructor(
          entries: List<Entry>,
      ) : this(
          map = KeyValueMap(entries = entries),
      )
    }

    companion object {
      fun decodeFromListBlock(
          list: MarkdownBlock.ListBlock,
      ): KeyValueMap = KeyValueMap(entries = list.items.map(::decodeEntry))

      private fun decodeEntry(
          item: MarkdownBlock.ListBlock.Item,
      ): Entry {
        val firstBlock =
            item.blocks.firstOrNull() as? MarkdownBlock.Paragraph
                ?: throw IllegalMarkdownEncodingException(
                    "Expected list item to start with a paragraph"
                )

        val (key, inlineValue) = decodeLabelParagraph(firstBlock)

        val node =
            when (item.blocks.size) {
              1 -> InlineValue(inlineContent = inlineValue)
              2 -> {
                require(inlineValue.isEmpty()) {
                  "Expected labeled block item to use a standalone key paragraph"
                }

                when (val secondBlock = item.blocks[1]) {
                  is MarkdownBlock.ListBlock -> Group(map = decodeFromListBlock(secondBlock))
                  else ->
                      throw IllegalMarkdownEncodingException(
                          "Unsupported block following labeled paragraph: ${secondBlock::class.simpleName}"
                      )
                }
              }

              else ->
                  throw IllegalMarkdownEncodingException(
                      "Expected key-value list item to contain one or two blocks"
                  )
            }

        return Entry(key = key, node = node)
      }

      private fun decodeLabelParagraph(
          paragraph: MarkdownBlock.Paragraph,
      ): Pair<String, List<MarkdownInline>> {
        val firstInline =
            paragraph.inlineContent.firstOrNull() as? MarkdownInline.Text
                ?: throw IllegalMarkdownEncodingException("Expected key label to start with text")

        val separatorIndex = firstInline.text.indexOf(':')
        require(separatorIndex > 0) { "Expected key label to contain ':' separator" }

        val key = firstInline.text.substring(0, separatorIndex)
        val remainder = firstInline.text.substring(separatorIndex + 1).removePrefix(" ")

        val valueInline = buildList {
          if (remainder.isNotEmpty()) {
            add(MarkdownInline.Text(remainder))
          }

          addAll(paragraph.inlineContent.drop(1))
        }

        return key to valueInline
      }
    }

    fun encodeToListBlock(): MarkdownBlock.ListBlock {
      return MarkdownBlock.ListBlock(
          ordered = false,
          items = entries.map { entry -> entry.node.encodeToListItem(key = entry.key) },
      )
    }
  }

  fun extractSingleTopLevelChapter(
      document: MarkdownDocument,
  ): MarkdownChapter =
      document.chapters.singleOrNull()
          ?: throw IllegalMarkdownEncodingException("Expected exactly one top-level chapter")

  fun extractSubChaptersNotExpectingIntroBlocks(
      chapter: MarkdownChapter,
  ): List<MarkdownChapter> {
    if (chapter.blocks.isNotEmpty()) {
      throw IllegalMarkdownEncodingException(
          "Expected chapter without intro blocks: ${chapter.title}"
      )
    }

    return chapter.subChapters
  }

  fun extractLeafChapters(
      chapter: MarkdownChapter,
  ): List<MarkdownChapter> {
    if (chapter.blocks.isNotEmpty()) {
      throw IllegalMarkdownEncodingException(
          "Expected chapter without intro blocks: ${chapter.title}"
      )
    }

    if (chapter.subChapters.any { it.subChapters.isNotEmpty() }) {
      throw IllegalMarkdownEncodingException(
          "Expected only leaf sub-chapters under: ${chapter.title}"
      )
    }

    return chapter.subChapters
  }
}
