package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.ControlChar
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath

data object VedWorktree_renderingUtils {
  fun VedWorktree.renderDirectoryTree(): MdChapter =
      MdChapter(
          title =
              MdInlineContent(
                  inlineNodes =
                      listOf(
                          MdInlineNode.Text("Worktree state"),
                      ),
              ),
          element =
              MdElement(
                  listOf(
                      MdBlock.Paragraph.of("This is the most recent state of the worktree."),
                      MdBlock.ListBlock(
                          topLevel =
                              MdBlock.ListBlock.Level(
                                  items =
                                      listOf(
                                          MiniItemRenderer.DirectoryRenderer(
                                                  directory = rootDirectory,
                                              )
                                              .render(
                                                  entityName = "", // `/` is appended automatically
                                                  entityStatus =
                                                      GitWorktreeEntity.Status.Considered(
                                                          GitWorktreeFilter.Classification.Include,
                                                      ),
                                              ),
                                      ),
                              ),
                      ),
                  ),
              ),
          subChapters = emptyList(),
      )

  fun VedWorktree.renderFiles(): MdChapter {
    val fileContentChapters =
        rootDirectory
            .visitOpenedFiles(
                directoryPath = UfsAbsolutePath.Root,
            )
            .map { visitedFile ->
              visitedFile.openedFile.renderFileContent(
                  filePath = visitedFile.filePath,
              )
            }
            .toList()

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
                                  "The content of these files is likely relevant to The Task. Line numbers followed by the a RS control character are not a part of the literal file content."
                            },
                        ),
                    ),
            ),
        subChapters = fileContentChapters,
    )
  }

  private fun VedExpandedDirectory.renderMiniTree(): MdBlock.ListBlock.Level? =
      MdBlock.ListBlock.Level.of(
          items =
              labeledEntityByName.entries
                  .sortedBy { (name, _) -> name.content }
                  .map { (name, labeledChildEntity) ->
                    val childStatus = labeledChildEntity.status
                    val childEntity = labeledChildEntity.entity

                    childEntity
                        .buildItemRenderer()
                        .render(
                            entityName = name.content,
                            entityStatus = childStatus,
                        )
                  },
      )

  private fun VedOpenedFile.renderFileContent(
      filePath: UfsLiteralAbsolutePath,
  ): MdChapter =
      MdChapter.leaf(
          title =
              MdInlineContent(
                  inlineNodes =
                      listOf(
                          MdInlineNode.Code(code = filePath.toUnixAbsolutePathString()),
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

  private sealed class MiniItemRenderer {
    class DirectoryRenderer(
        private val directory: VedDirectory,
    ) : MiniItemRenderer() {
      override val nameSuffix: String
        get() = "/"

      override val extraLabels: List<String>
        get() =
            when (directory) {
              is VedCollapsedDirectory -> listOf("collapsed")

              is VedExpandedDirectory ->
                  when {
                    directory.labeledEntityByName.isEmpty() -> listOf("expanded", "empty")
                    else -> listOf("expanded")
                  }
            }

      override fun renderNested(): MdBlock.ListBlock.Level? {
        val expandedDirectory = directory as? VedExpandedDirectory ?: return null
        return expandedDirectory.renderMiniTree()
      }
    }

    class FileRenderer(
        private val file: VedFile,
    ) : MiniItemRenderer() {
      override val nameSuffix: String
        get() = ""

      override val extraLabels: List<String>
        get() =
            when (file) {
              is VedOpenedFile -> listOf("opened")
              VedClosedFile -> listOf("closed")
            }

      override fun renderNested(): MdBlock.ListBlock.Level? = null
    }

    fun render(
        entityName: String,
        entityStatus: GitWorktreeEntity.Status,
    ): MdBlock.ListBlock.Item {
      val statusLabel =
          when (entityStatus) {
            is GitWorktreeEntity.Status.Considered ->
                when (entityStatus.classification) {
                  GitWorktreeFilter.Classification.Ignore -> "ignored"
                  GitWorktreeFilter.Classification.Include -> "included"
                }

            is GitWorktreeEntity.Status.NonConsidered -> "non-considered"
          }

      val allLabels = listOf(statusLabel) + extraLabels

      return MdBlock.ListBlock.Item(
          content =
              MdInlineContent(
                  inlineNodes =
                      listOf(
                          MdInlineNode.Code(
                              code = "${entityName}$nameSuffix",
                          ),
                          MdInlineNode.Text(
                              text = " (${allLabels.joinToString()})",
                          ),
                      ),
              ),
          nestedLevel = renderNested(),
      )
    }

    abstract val nameSuffix: String

    abstract val extraLabels: List<String>

    abstract fun renderNested(): MdBlock.ListBlock.Level?
  }

  private fun VedEntity.buildItemRenderer(): MiniItemRenderer =
      when (this) {
        is VedDirectory -> MiniItemRenderer.DirectoryRenderer(directory = this)
        is VedFile -> MiniItemRenderer.FileRenderer(file = this)
      }
}
