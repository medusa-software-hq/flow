package software.medusa.flow.virtual_editor.flat_worktree

import software.medusa.commons.markdown.ControlChar
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode

data object VedFlatWorktree_renderingUtils {
  /**
   * Renders the open files as an "Open files" chapter, one sub-chapter per content version. Because
   * [VedFlatWorktree.openedFiles] is ordered by modification timestamp, appending a newly opened or
   * freshly patched file only grows the tail — the rendered prefix stays byte-for-byte stable
   * across rounds, which keeps the (token-heavy) file content prompt-cache friendly.
   */
  fun VedFlatWorktree.renderFiles(): MdChapter {
    val fileContentChapters = openedFiles.map { openedFile -> openedFile.renderFileContent() }

    return MdChapter.wrapper(
        title =
            MdInlineContent(
                inlineNodes =
                    listOf(
                        MdInlineNode.Text("Open files"),
                    ),
            ),
        introElement =
            MdElement(
                blocks =
                    listOf(
                        MdBlock.Paragraph.of(
                            when {
                              fileContentChapters.isEmpty() -> "No files are open."
                              else ->
                                  "The content of these files is likely relevant to The Task. A file may appear more than once as it is revised; for a given path, the entry with the highest t holds its current content. Line numbers followed by an RS control character are not a part of the literal file content."
                            },
                        ),
                    ),
            ),
        subChapters = fileContentChapters,
    )
  }

  private fun VedFlatOpenedFile.renderFileContent(): MdChapter =
      MdChapter.leaf(
          title =
              MdInlineContent(
                  inlineNodes =
                      listOf(
                          MdInlineNode.Code(code = path.toUnixAbsolutePathString()),
                          MdInlineNode.Text(text = " (t = ${modificationTimestamp.t})"),
                      ),
              ),
          element =
              MdElement(
                  listOf(
                      MdBlock.CodeBlock(
                          code =
                              content.indexedLines.joinToString("") { indexedLine ->
                                "${indexedLine.index.indexOneBased}${ControlChar.RS}${indexedLine.line.content}\n"
                              },
                      ),
                  ),
              ),
      )
}
