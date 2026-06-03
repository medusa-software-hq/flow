package software.medusa.code_agent.virtual_workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace_markdownUtils.encodeToMarkdownDocument
import software.medusa.code_agent.virtual_workspace.document.CodeDocument
import software.medusa.code_agent.virtual_workspace.document.CodeLanguage
import software.medusa.code_agent.virtual_workspace.document.CodeNode
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.paths.UnixPath
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownChapter
import software.medusa.markdown.MarkdownDocument
import software.medusa.markdown.MarkdownInline

class CodeVirtualWorkspace_markdownUtils_tests {
  @Test
  fun test_encodeToMarkdownDocument() {
    val workspace =
        CodeVirtualWorkspace(
            rootDirectory =
                CodeVirtualWorkspace.ExpandedDirectory(
                    childEntityByName =
                        mapOf(
                            UnixPath.Name.Literal("foo") to
                                CodeVirtualWorkspace.ExpandedDirectory(
                                    childEntityByName =
                                        mapOf(
                                            UnixPath.Name.Literal("bar") to
                                                CodeVirtualWorkspace.ExpandedDirectory(
                                                    childEntityByName =
                                                        mapOf(
                                                            UnixPath.Name.Literal("baz.txt") to
                                                                CodeVirtualWorkspace.OpenedCodeFile(
                                                                    vcsStatus =
                                                                        CodeVirtualWorkspace
                                                                            .VcsStatus
                                                                            .Included,
                                                                    document =
                                                                        documentOf(
                                                                            "foo",
                                                                            "content goes",
                                                                            "here",
                                                                        ),
                                                                ),
                                                            UnixPath.Name.Literal("asdf.txt") to
                                                                CodeVirtualWorkspace.OpenedCodeFile(
                                                                    vcsStatus =
                                                                        CodeVirtualWorkspace
                                                                            .VcsStatus
                                                                            .Included,
                                                                    document =
                                                                        documentOf(
                                                                            "asdf",
                                                                            "content goes",
                                                                            "here",
                                                                        ),
                                                                ),
                                                            UnixPath.Name.Literal("out.exe") to
                                                                CodeVirtualWorkspace.ClosedFile(
                                                                    vcsStatus =
                                                                        CodeVirtualWorkspace
                                                                            .VcsStatus
                                                                            .Ignored,
                                                                    lockState =
                                                                        CodeVirtualWorkspace
                                                                            .LockState
                                                                            .Locked,
                                                                ),
                                                        ),
                                                ),
                                            UnixPath.Name.Literal("gen") to
                                                CodeVirtualWorkspace.CollapsedDirectory(
                                                    vcsStatus =
                                                        CodeVirtualWorkspace.VcsStatus.Ignored,
                                                    lockState =
                                                        CodeVirtualWorkspace.LockState.Unlocked,
                                                ),
                                            UnixPath.Name.Literal("emptydir") to
                                                CodeVirtualWorkspace.ExpandedDirectory(
                                                    childEntityByName = emptyMap(),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    val actualMarkdownDocument = workspace.encodeToMarkdownDocument()

    assertEquals(
        expected =
            MarkdownDocument(
                chapters =
                    listOf(
                        MarkdownChapter.wrapper(
                            title = listOf(MarkdownInline.Text("Workspace")),
                            subChapters =
                                listOf(
                                    MarkdownChapter.wrapper(
                                        title = listOf(MarkdownInline.Text("Labels")),
                                        subChapters =
                                            listOf(
                                                MarkdownChapter.leaf(
                                                    title =
                                                        listOf(MarkdownInline.Text("VCS status")),
                                                    blocks =
                                                        listOf(
                                                            MarkdownBlock.ListBlock(
                                                                items =
                                                                    listOf(
                                                                        labelItem(
                                                                            label = "included",
                                                                            description =
                                                                                "the file or directory is included by the VCS",
                                                                            detail =
                                                                                "An included directory without any included descendant files won't be tracked",
                                                                        ),
                                                                        labelItem(
                                                                            label = "ignored",
                                                                            description =
                                                                                "the file or directory is ignored by the VCS",
                                                                            detail =
                                                                                "Ignored files/directories are not tracked by the VCS, but they are still present in the workspace",
                                                                        ),
                                                                        labelItem(
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
                                                    title =
                                                        listOf(
                                                            MarkdownInline.Text("Directory state")
                                                        ),
                                                    blocks =
                                                        listOf(
                                                            MarkdownBlock.ListBlock(
                                                                items =
                                                                    listOf(
                                                                        labelItem(
                                                                            label = "expanded",
                                                                            description =
                                                                                "children are listed below",
                                                                        ),
                                                                        labelItem(
                                                                            label = "collapsed",
                                                                            description =
                                                                                "children are not listed (expand the directory to list children)",
                                                                        ),
                                                                        labelItem(
                                                                            label = "empty",
                                                                            description =
                                                                                "directory has no children",
                                                                        ),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                                MarkdownChapter.leaf(
                                                    title =
                                                        listOf(MarkdownInline.Text("File state")),
                                                    blocks =
                                                        listOf(
                                                            MarkdownBlock.ListBlock(
                                                                items =
                                                                    listOf(
                                                                        labelItem(
                                                                            label = "opened",
                                                                            description =
                                                                                "file contents are included below",
                                                                        ),
                                                                        labelItem(
                                                                            label = "closed",
                                                                            description =
                                                                                "file contents are not included (open the file to include contents)",
                                                                        ),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                    MarkdownChapter.leaf(
                                        title = listOf(MarkdownInline.Text("Tree")),
                                        blocks =
                                            listOf(
                                                MarkdownBlock.ListBlock(
                                                    items =
                                                        listOf(
                                                            treeDirectoryItem(
                                                                name = "foo",
                                                                labels =
                                                                    listOf("included", "expanded"),
                                                                childItems =
                                                                    listOf(
                                                                        treeDirectoryItem(
                                                                            name = "bar",
                                                                            labels =
                                                                                listOf(
                                                                                    "included",
                                                                                    "expanded",
                                                                                ),
                                                                            childItems =
                                                                                listOf(
                                                                                    treeFileItem(
                                                                                        name =
                                                                                            "asdf.txt",
                                                                                        labels =
                                                                                            listOf(
                                                                                                "included",
                                                                                                "opened",
                                                                                            ),
                                                                                    ),
                                                                                    treeFileItem(
                                                                                        name =
                                                                                            "baz.txt",
                                                                                        labels =
                                                                                            listOf(
                                                                                                "included",
                                                                                                "opened",
                                                                                            ),
                                                                                    ),
                                                                                    treeFileItem(
                                                                                        name =
                                                                                            "out.exe",
                                                                                        labels =
                                                                                            listOf(
                                                                                                "ignored",
                                                                                                "closed",
                                                                                            ),
                                                                                    ),
                                                                                ),
                                                                        ),
                                                                        treeDirectoryItem(
                                                                            name = "emptydir",
                                                                            labels =
                                                                                listOf(
                                                                                    "included",
                                                                                    "empty",
                                                                                ),
                                                                        ),
                                                                        treeDirectoryItem(
                                                                            name = "gen",
                                                                            labels =
                                                                                listOf(
                                                                                    "ignored",
                                                                                    "collapsed",
                                                                                ),
                                                                        ),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                    MarkdownChapter.wrapper(
                                        title = listOf(MarkdownInline.Text("Opened files")),
                                        subChapters =
                                            listOf(
                                                MarkdownChapter.leaf(
                                                    title =
                                                        listOf(
                                                            MarkdownInline.Code("/foo/bar/asdf.txt")
                                                        ),
                                                    blocks =
                                                        listOf(
                                                            MarkdownBlock.CodeBlock(
                                                                code = "asdf\ncontent goes\nhere",
                                                            ),
                                                        ),
                                                ),
                                                MarkdownChapter.leaf(
                                                    title =
                                                        listOf(
                                                            MarkdownInline.Code("/foo/bar/baz.txt")
                                                        ),
                                                    blocks =
                                                        listOf(
                                                            MarkdownBlock.CodeBlock(
                                                                code = "foo\ncontent goes\nhere",
                                                            ),
                                                        ),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            ),
        actual = actualMarkdownDocument,
    )
  }

  private fun labelItem(
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
                  detail?.let {
                    MarkdownBlock.ListBlock(
                        items =
                            listOf(MarkdownBlock.ListBlock.Item.inline(MarkdownInline.Text(it))),
                    )
                  },
              ),
      )

  private fun treeDirectoryItem(
      name: String,
      labels: List<String>,
      childItems: List<MarkdownBlock.ListBlock.Item>? = null,
  ): MarkdownBlock.ListBlock.Item =
      MarkdownBlock.ListBlock.Item(
          blocks =
              listOfNotNull(
                  MarkdownBlock.Paragraph(
                      inlineContent =
                          listOf(
                              MarkdownInline.Code("$name/"),
                              MarkdownInline.Text(" [${labels.joinToString(separator = ", ")}]"),
                          ),
                  ),
                  childItems?.let { MarkdownBlock.ListBlock(items = it) },
              ),
      )

  private fun treeFileItem(
      name: String,
      labels: List<String>,
  ): MarkdownBlock.ListBlock.Item =
      MarkdownBlock.ListBlock.Item.inline(
          MarkdownInline.Code(name),
          MarkdownInline.Text(" [${labels.joinToString(separator = ", ")}]"),
      )

  private fun documentOf(vararg lines: String): CodeDocument =
      CodeDocument(
          language = CodeLanguage.Kotlin,
          rootContainer =
              CodeNode.Container(
                  nodes = listOf(CodeNode.Plain(plainBlock = CodeBlock.of(*lines))),
              ),
      )
}
