package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.ControlChar
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.markdown.MdInlineNode

/**
 * The leader's board rendering: a directory tree in which **exposed** files render their current
 * content, **opened-but-hidden** files collapse to a one-line stub (path, ~size, opened/edited
 * timestamps), and everything else lists by name — plus the exposure [VedExposureMeter] header.
 *
 * This is a leader-only view; the assistant orientation tree ([VedWorktree_renderingUtils]) is
 * untouched and the classic engine never calls this, so classic behaviour stays bit-identical.
 */
data object VedWorktree_leaderRenderingUtils {
  fun VedWorktree.renderLeaderBoard(
      softBudgetTokens: Int,
  ): MdChapter {
    val meter = VedExposureMeter.of(worktree = this)

    val exposedFiles =
        visitOpenedFiles()
            .filter { it.openedFile.exposure == VedExposure.Exposed }
            .sortedBy { it.filePath.toUnixAbsolutePathString() }
            .toList()

    return MdChapter(
        title = MdInlineContent(inlineNodes = listOf(MdInlineNode.Text("Leader board"))),
        element =
            MdElement(
                listOf(
                    MdBlock.Paragraph.of(meter.render(softBudgetTokens = softBudgetTokens)),
                    MdBlock.Paragraph.of(
                        "Worktree tree below. Exposed files render in full as sub-sections; files opened but not exposed show a one-line stub; other files list by name only.",
                    ),
                    MdBlock.ListBlock(
                        topLevel =
                            MdBlock.ListBlock.Level(
                                items =
                                    listOf(
                                        LeaderItemRenderer.DirectoryRenderer(
                                                directory = rootDirectory
                                            )
                                            .render(
                                                entityName = "", // `/` is appended automatically
                                                entityStatus = GitWorktreeEntity.Status.included,
                                            ),
                                    ),
                            ),
                    ),
                ),
            ),
        subChapters = exposedFiles.map { it.renderExposedContent() },
    )
  }

  // Current content only (not the version history) — the leader sees the settled board, timestamps
  // it cites come from the tree stubs. Line numbering matches the assistant file rendering.
  private fun VedOpenedFile.Visited.renderExposedContent(): MdChapter =
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
                              openedFile.currentContent.indexedLines.joinToString("") { indexedLine
                                ->
                                "${indexedLine.index.indexOneBased}${ControlChar.RS}${indexedLine.line.content}\n"
                              },
                      ),
                  ),
              ),
      )

  private fun VedExpandedDirectory.renderMiniTree(): MdBlock.ListBlock.Level? =
      MdBlock.ListBlock.Level.of(
          items =
              labeledEntityByName.entries
                  .sortedBy { (name, _) -> name.content }
                  .map { (name, labeledChildEntity) ->
                    labeledChildEntity.entity
                        .buildItemRenderer()
                        .render(
                            entityName = name.content,
                            entityStatus = labeledChildEntity.status,
                        )
                  },
      )

  private sealed class LeaderItemRenderer {
    class DirectoryRenderer(
        private val directory: VedDirectory,
    ) : LeaderItemRenderer() {
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
    ) : LeaderItemRenderer() {
      override val nameSuffix: String
        get() = ""

      override val extraLabels: List<String>
        get() =
            when (file) {
              VedClosedFile -> listOf("closed")

              is VedOpenedFile ->
                  when (file.exposure) {
                    // Exposed: the content is rendered in a sub-section, so the tree just flags it.
                    VedExposure.Exposed -> listOf("opened", "exposed")

                    // Hidden: the one-line stub — size and the timestamps the leader can cite.
                    VedExposure.Hidden ->
                        buildList {
                          add("opened")
                          add("hidden")
                          add(
                              "~${VedExposureMeter.formatCount(file.currentContent.dump().length)} chars",
                          )
                          add("opened at t=${file.openedTimestamp.t}")
                          if (file.isEdited) add("edited at t=${file.lastEditedTimestamp.t}")
                        }
                  }
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

  private fun VedEntity.buildItemRenderer(): LeaderItemRenderer =
      when (this) {
        is VedDirectory -> LeaderItemRenderer.DirectoryRenderer(directory = this)
        is VedFile -> LeaderItemRenderer.FileRenderer(file = this)
      }
}
