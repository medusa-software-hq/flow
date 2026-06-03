package software.medusa.code_agent.virtual_workspace

import software.medusa.code_agent.virtual_workspace.document.CodeDocument
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.resolve
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline

data object CodeVirtualWorkspace_markdownUtils {
  fun CodeVirtualWorkspace.encodeToMarkdownDocument(): MarkdownDocument {
    val treeItems = rootDirectory.encodeDirectoryTreeItems(directoryName = null)

    val openedFiles =
        rootDirectory
            .collectOpenedCodeFiles(
                basePath = AbsoluteUnixPath.Root,
            )
            .sortedBy { (path, _) -> path.toUnixAbsolutePathString() }

    return MarkdownDocument(
        chapters =
            listOf(
                MarkdownChapter.wrapper(
                    title = listOf(MarkdownInline.Text("Workspace")),
                    subChapters =
                        listOf(
                            buildLabelsChapter(),
                            MarkdownChapter.leaf(
                                title = listOf(MarkdownInline.Text("Tree")),
                                blocks = listOf(MarkdownBlock.ListBlock(items = treeItems)),
                            ),
                            MarkdownChapter.wrapper(
                                title = listOf(MarkdownInline.Text("Opened files")),
                                subChapters =
                                    openedFiles.map { (path, file) ->
                                      file.toOpenedFileChapter(filePath = path)
                                    },
                            ),
                        ),
                ),
            ),
    )
  }

  private fun buildLabelsChapter(): MarkdownChapter =
      MarkdownChapter.wrapper(
          title = listOf(MarkdownInline.Text("Labels")),
          subChapters =
              listOf(
                  MarkdownChapter.leaf(
                      title = listOf(MarkdownInline.Text("VCS status")),
                      blocks =
                          listOf(
                              MarkdownBlock.ListBlock(
                                  items =
                                      listOf(
                                          labeledItem(
                                              label = "included",
                                              description =
                                                  "the file or directory is included by the VCS",
                                              detail =
                                                  "An included directory without any included descendant files won't be tracked",
                                          ),
                                          labeledItem(
                                              label = "ignored",
                                              description =
                                                  "the file or directory is ignored by the VCS",
                                              detail =
                                                  "Ignored files/directories are not tracked by the VCS, but they are still present in the workspace",
                                          ),
                                          labeledItem(
                                              label = "excluded",
                                              description =
                                                  "the file or directory is a descendant of an ignored directory",
                                              detail =
                                                  "Excluded files/directories aren't even considered by the VCS",
                                          ),
                                      ),
                              ),
                          ),
                  ),
                  MarkdownChapter.leaf(
                      title = listOf(MarkdownInline.Text("Directory state")),
                      blocks =
                          listOf(
                              MarkdownBlock.ListBlock(
                                  items =
                                      listOf(
                                          labeledItem(
                                              label = "expanded",
                                              description = "children are listed below",
                                          ),
                                          labeledItem(
                                              label = "collapsed",
                                              description =
                                                  "children are not listed (expand the directory to list children)",
                                          ),
                                          labeledItem(
                                              label = "empty",
                                              description = "directory has no children",
                                          ),
                                      ),
                              ),
                          ),
                  ),
                  MarkdownChapter.leaf(
                      title = listOf(MarkdownInline.Text("File state")),
                      blocks =
                          listOf(
                              MarkdownBlock.ListBlock(
                                  items =
                                      listOf(
                                          labeledItem(
                                              label = "opened",
                                              description = "file contents are included below",
                                          ),
                                          labeledItem(
                                              label = "closed",
                                              description =
                                                  "file contents are not included (open the file to include contents)",
                                          ),
                                      ),
                              ),
                          ),
                  ),
              ),
      )

  private fun labeledItem(
      label: String,
      description: String,
      detail: String? = null,
  ): MarkdownBlock.ListBlock.Item =
      MarkdownBlock.ListBlock.Item(
          blocks =
              listOfNotNull(
                  MarkdownBlock.Paragraph(
                      inlineContent =
                          listOf(
                              MarkdownInline.Code(label),
                              MarkdownInline.Text(": $description"),
                          ),
                  ),
                  detail?.let { detailText ->
                    MarkdownBlock.ListBlock(
                        items =
                            listOf(
                                MarkdownBlock.ListBlock.Item.inline(
                                    MarkdownInline.Text(detailText),
                                ),
                            ),
                    )
                  },
              ),
      )

  private fun CodeVirtualWorkspace.ExpandedDirectory.encodeDirectoryTreeItems(
      directoryName: UnixPath.Name.Literal?,
  ): List<MarkdownBlock.ListBlock.Item> {
    val childItems =
        childEntityByName.entries
            .sortedBy { (name, _) -> name.name }
            .map { (name, childEntity) -> childEntity.toTreeItem(name = name) }

    if (directoryName == null) {
      return childItems
    }

    return listOf(
        directoryTreeItem(
            directoryName = directoryName,
            vcsStatus = CodeVirtualWorkspace.VcsStatus.Included,
            directoryState =
                when {
                  childItems.isEmpty() -> "empty"
                  else -> "expanded"
                },
            childItems = childItems.takeIf { it.isNotEmpty() },
        ),
    )
  }

  private fun CodeVirtualWorkspace.Entity.toTreeItem(
      name: UnixPath.Name.Literal,
  ): MarkdownBlock.ListBlock.Item =
      when (this) {
        is CodeVirtualWorkspace.ExpandedDirectory ->
            directoryTreeItem(
                directoryName = name,
                vcsStatus = vcsStatus,
                directoryState =
                    when {
                      childEntityByName.isEmpty() -> "empty"
                      else -> "expanded"
                    },
                childItems =
                    childEntityByName.entries
                        .sortedBy { (childName, _) -> childName.name }
                        .map { (childName, childEntity) ->
                          childEntity.toTreeItem(
                              name = childName,
                          )
                        }
                        .takeIf { it.isNotEmpty() },
            )

        is CodeVirtualWorkspace.CollapsedDirectory ->
            directoryTreeItem(
                directoryName = name,
                vcsStatus = vcsStatus,
                directoryState = "collapsed",
                childItems = null,
            )

        is CodeVirtualWorkspace.OpenedCodeFile ->
            fileTreeItem(
                fileName = name,
                labels = listOf(vcsStatus.toMarkdownLabel(), "opened"),
            )

        is CodeVirtualWorkspace.ClosedFile ->
            fileTreeItem(
                fileName = name,
                labels = listOf(vcsStatus.toMarkdownLabel(), "closed"),
            )
      }

  private fun directoryTreeItem(
      directoryName: UnixPath.Name.Literal,
      vcsStatus: CodeVirtualWorkspace.VcsStatus,
      directoryState: String,
      childItems: List<MarkdownBlock.ListBlock.Item>?,
  ): MarkdownBlock.ListBlock.Item =
      MarkdownBlock.ListBlock.Item(
          blocks =
              listOfNotNull(
                  MarkdownBlock.Paragraph(
                      inlineContent =
                          listOf(
                              MarkdownInline.Code("${directoryName.name}/"),
                              MarkdownInline.Text(
                                  " (${vcsStatus.toMarkdownLabel()}, $directoryState)"
                              ),
                          ),
                  ),
                  childItems?.let { MarkdownBlock.ListBlock(items = it) },
              ),
      )

  private fun fileTreeItem(
      fileName: UnixPath.Name.Literal,
      labels: List<String>,
  ): MarkdownBlock.ListBlock.Item =
      MarkdownBlock.ListBlock.Item.inline(
          MarkdownInline.Code(fileName.name),
          MarkdownInline.Text(" (${labels.joinToString(separator = ", ")})"),
      )

  private fun CodeVirtualWorkspace.OpenedCodeFile.toOpenedFileChapter(
      filePath: AbsoluteUnixPath<UnixPath.Name.Literal>,
  ): MarkdownChapter {
    val fileContent =
        with(CodeDocument.DumpContentContext(language = document.language)) {
          document.dumpContent()
        }

    return MarkdownChapter.leaf(
        title = listOf(MarkdownInline.Code(filePath.toUnixAbsolutePathString())),
        blocks = listOf(MarkdownBlock.CodeBlock(code = fileContent.dump().removeSuffix("\n"))),
    )
  }

  private fun CodeVirtualWorkspace.ExpandedDirectory.collectOpenedCodeFiles(
      basePath: AbsoluteUnixPath<UnixPath.Name.Literal>,
  ): List<Pair<AbsoluteUnixPath<UnixPath.Name.Literal>, CodeVirtualWorkspace.OpenedCodeFile>> =
      buildList {
        for ((name, childEntity) in childEntityByName) {
          val childPath: AbsoluteUnixPath<UnixPath.Name.Literal> = basePath.resolve(name)

          when (childEntity) {
            is CodeVirtualWorkspace.ExpandedDirectory ->
                addAll(childEntity.collectOpenedCodeFiles(basePath = childPath))

            is CodeVirtualWorkspace.OpenedCodeFile -> add(childPath to childEntity)

            is CodeVirtualWorkspace.CollapsedDirectory,
            is CodeVirtualWorkspace.ClosedFile,
            -> {}
          }
        }
      }

  private fun CodeVirtualWorkspace.VcsStatus.toMarkdownLabel(): String =
      when (this) {
        CodeVirtualWorkspace.VcsStatus.Included -> "included"
        CodeVirtualWorkspace.VcsStatus.Ignored -> "ignored"
        CodeVirtualWorkspace.VcsStatus.NonConsidered -> "excluded"
      }
}
