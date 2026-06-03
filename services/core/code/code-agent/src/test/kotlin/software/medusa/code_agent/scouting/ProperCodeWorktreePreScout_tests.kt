package software.medusa.code_agent.scouting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.LockState
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.VcsStatus
import software.medusa.code_agent.virtual_workspace.document.CodeDocument
import software.medusa.code_agent.virtual_workspace.document.CodeDocumentBootstrapper
import software.medusa.code_agent.virtual_workspace.document.CodeLanguage
import software.medusa.code_agent.virtual_workspace.document.CodeNode
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.impl.immutable.ImmutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.impl.immutable.ImmutableCompatFsFile
import software.medusa.commons.filesystem.compat.readText
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.git.worktree.GitFsNodeKind
import software.medusa.git.worktree.GitIncludedWorktreeDirectory
import software.medusa.git.worktree.GitIncludedWorktreeDirectory.LocalFilterLoader
import software.medusa.git.worktree.GitWorktree
import software.medusa.git.worktree.GitWorktreeFilter
import software.medusa.markdown.MarkdownBlock
import software.medusa.markdown.MarkdownInline

class ProperCodeWorktreePreScout_tests {
  enum class MyMarker : FakeLocalFilterLoader.Marker {
    Root {
      override val id = "root"
    },
    Src {
      override val id = "src"
    },
  }

  companion object {
    private fun buildSummary(
        title: CodeNode.Region.Title,
    ): CodeNode.Region.Summary =
        CodeNode.Region.Summary(
            paragraph =
                MarkdownBlock.Paragraph(
                    inlineContent = listOf(MarkdownInline.Text("Summary for ${title.text.text}")),
                ),
        )

    private val binaryByteString = ByteString(0.toByte(), 1.toByte(), 2.toByte(), 3.toByte())

    private val srcDirectoryName = UnixPath.Name.Literal("src")
    private val buildDirectoryName = UnixPath.Name.Literal("build")
    private val srcProperFile1Name = UnixPath.Name.Literal("file1.kt")
    private val srcProperFile2Name = UnixPath.Name.Literal("file2.kt")
    private val srcTrashFileName = UnixPath.Name.Literal("trash.txt")
    private val executableFileName = UnixPath.Name.Literal("out.exe")

    private val primaryFooTitle =
        CodeNode.Region.Title(
            text = MarkdownInline.Text("Primary Foo functionality"),
        )

    private val srcProperFile1CodeBlock =
        CodeBlock.of(
            "class Foo {",
            "  //#region Primary Foo functionality",
            "  fun run() = Unit",
            "  //#endregion",
            "}",
        )

    private val expandedSrcProperFile1Document =
        CodeDocument(
            language = CodeLanguage.Kotlin,
            rootContainer =
                CodeNode.Container(
                    nodes =
                        listOf(
                            CodeNode.Plain(
                                plainBlock = CodeBlock.of("class Foo {"),
                            ),
                            CodeNode.Region(
                                title = primaryFooTitle,
                                summary = buildSummary(title = primaryFooTitle),
                                state = CodeNode.Region.State.Expanded,
                                indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                                innerContainer =
                                    CodeNode.Container(
                                        nodes =
                                            listOf(
                                                CodeNode.Plain(
                                                    plainBlock = CodeBlock.of("  fun run() = Unit"),
                                                ),
                                            ),
                                    ),
                            ),
                            CodeNode.Plain(
                                plainBlock = CodeBlock.of("}"),
                            ),
                        ),
                ),
        )

    private val srcProperFile2CodeBlock =
        CodeBlock.of(
            "class Foo2 {",
            "  //#region Secondary behavior",
            "  fun f1() = 1",
            "  //#endregion",
            "}",
        )

    private val secondaryBehaviorTitle =
        CodeNode.Region.Title(
            text = MarkdownInline.Text("Secondary behavior"),
        )

    private val collapsedSrcProperFile2Document =
        CodeDocument(
            language = CodeLanguage.Kotlin,
            rootContainer =
                CodeNode.Container(
                    nodes =
                        listOf(
                            CodeNode.Plain(
                                plainBlock = CodeBlock.of("class Foo2 {"),
                            ),
                            CodeNode.Region(
                                title = secondaryBehaviorTitle,
                                summary = buildSummary(title = secondaryBehaviorTitle),
                                state = CodeNode.Region.State.Collapsed,
                                indentationLevel = CodeBlock.IndentationLevel(spaceCount = 2),
                                innerContainer =
                                    CodeNode.Container(
                                        nodes =
                                            listOf(
                                                CodeNode.Plain(
                                                    plainBlock = CodeBlock.of("  fun f1() = 1"),
                                                ),
                                            ),
                                    ),
                            ),
                            CodeNode.Plain(
                                plainBlock = CodeBlock.of("}"),
                            ),
                        ),
                ),
        )

    private val srcTrashFileCodeBlock =
        CodeBlock.of(
            "trash",
            "more trash",
            "trash",
            "trash",
        )

    private val srcTrashDocument =
        CodeDocument(
            language = CodeLanguage.Kotlin,
            rootContainer =
                CodeNode.Container(
                    nodes =
                        listOf(
                            CodeNode.Plain(
                                plainBlock = srcTrashFileCodeBlock,
                            ),
                        ),
                ),
        )

    private fun markerDocument(
        marker: MyMarker,
    ): CodeDocument =
        CodeDocument(
            language = CodeLanguage.Kotlin,
            rootContainer =
                CodeNode.Container(
                    nodes =
                        listOf(
                            CodeNode.Plain(
                                plainBlock = CodeBlock.of(marker.id),
                            ),
                        ),
                ),
        )
  }

  private object FakeDocumentBootstrapper : CodeDocumentBootstrapper {
    override suspend fun bootstrapDocument(document: CodeDocument): CodeDocument =
        document.copy(rootContainer = document.rootContainer.bootstrapRecursively())

    private fun CodeNode.Container.bootstrapRecursively(): CodeNode.Container =
        CodeNode.Container(
            nodes =
                nodes.map { node ->
                  when (node) {
                    is CodeNode.Plain -> node
                    is CodeNode.Region -> {
                      val title = node.title

                      node.copy(
                          summary = buildSummary(title = node.title),
                          state =
                              when {
                                title.text.text.contains("Primary") ->
                                    CodeNode.Region.State.Expanded
                                else -> CodeNode.Region.State.Collapsed
                              },
                          innerContainer = node.innerContainer.bootstrapRecursively(),
                      )
                    }
                  }
                },
        )
  }

  private data object RootFilter : GitWorktreeFilter {
    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): GitWorktreeFilter.Classification? {
      val fileName = path.fileName ?: return null

      return when {
        fileName.name.endsWith(".exe") -> GitWorktreeFilter.Classification.Ignore
        nodeKind == GitFsNodeKind.Directory && fileName == UnixPath.Name.Literal("build") ->
            GitWorktreeFilter.Classification.Ignore
        else -> null
      }
    }
  }

  private data object SrcFilter : GitWorktreeFilter {
    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): GitWorktreeFilter.Classification? {
      val fileName = path.fileName ?: return null

      return when {
        fileName.name.startsWith("trash.") -> GitWorktreeFilter.Classification.Ignore
        else -> null
      }
    }
  }

  @Test
  fun test_preScoutWorktree() = runTest {
    val rootDirectory =
        ImmutableCompatFsDirectory(
            childEntityByName =
                mapOf(
                    FakeLocalFilterLoader.inputEntry(MyMarker.Root),
                    srcDirectoryName to
                        ImmutableCompatFsDirectory(
                            childEntityByName =
                                mapOf(
                                    FakeLocalFilterLoader.inputEntry(MyMarker.Src),
                                    buildDirectoryName to
                                        ImmutableCompatFsDirectory(
                                            childEntityByName =
                                                mapOf(
                                                    executableFileName to
                                                        ImmutableCompatFsFile(
                                                            content = binaryByteString
                                                        ),
                                                ),
                                        ),
                                    srcProperFile1Name to
                                        ImmutableCompatFsFile(
                                            content = srcProperFile1CodeBlock.dump()
                                        ),
                                    srcProperFile2Name to
                                        ImmutableCompatFsFile(
                                            content = srcProperFile2CodeBlock.dump()
                                        ),
                                    srcTrashFileName to
                                        ImmutableCompatFsFile(
                                            content = srcTrashFileCodeBlock.dump()
                                        ),
                                ),
                        ),
                    buildDirectoryName to
                        ImmutableCompatFsDirectory(
                            childEntityByName =
                                mapOf(
                                    executableFileName to
                                        ImmutableCompatFsFile(content = binaryByteString),
                                    UnixPath.Name.Literal("info.txt") to
                                        ImmutableCompatFsFile(content = "Info"),
                                ),
                        ),
                ),
        )

    val rootGitWorktreeDirectory =
        with(
            FakeLocalFilterLoader(
                localFilterByMarker =
                    mapOf(
                        MyMarker.Root to RootFilter,
                        MyMarker.Src to SrcFilter,
                    ),
            ),
        ) {
          GitIncludedWorktreeDirectory.include(
              fsDirectory = rootDirectory,
              baseFilter = GitWorktreeFilter.Passive,
          )
        }

    val worktreeScout =
        ProperCodeWorktreePreScout(
            documentBootstrapper = FakeDocumentBootstrapper,
        )

    val scoutedVirtualWorkspace =
        worktreeScout.preScoutWorktree(
            gitWorktree =
                GitWorktree(
                    rootDirectory = rootGitWorktreeDirectory,
                ),
        )

    val expectedVirtualWorkspace =
        CodeVirtualWorkspace(
            rootDirectory =
                CodeVirtualWorkspace.ExpandedDirectory(
                    childEntityByName =
                        mapOf(
                            FakeLocalFilterLoader.expectedEntry(MyMarker.Root),
                            srcDirectoryName to
                                CodeVirtualWorkspace.ExpandedDirectory(
                                    childEntityByName =
                                        mapOf(
                                            FakeLocalFilterLoader.expectedEntry(MyMarker.Src),
                                            buildDirectoryName to
                                                CodeVirtualWorkspace.CollapsedDirectory(
                                                    vcsStatus = VcsStatus.Ignored,
                                                    lockState = LockState.Unlocked,
                                                ),
                                            srcProperFile1Name to
                                                CodeVirtualWorkspace.OpenedCodeFile(
                                                    vcsStatus = VcsStatus.Included,
                                                    document = expandedSrcProperFile1Document,
                                                ),
                                            srcProperFile2Name to
                                                CodeVirtualWorkspace.OpenedCodeFile(
                                                    vcsStatus = VcsStatus.Included,
                                                    document = collapsedSrcProperFile2Document,
                                                ),
                                            srcTrashFileName to
                                                CodeVirtualWorkspace.OpenedCodeFile(
                                                    vcsStatus = VcsStatus.Ignored,
                                                    document = srcTrashDocument,
                                                ),
                                        ),
                                ),
                            buildDirectoryName to
                                CodeVirtualWorkspace.CollapsedDirectory(
                                    vcsStatus = VcsStatus.Ignored,
                                    lockState = LockState.Unlocked,
                                ),
                        ),
                ),
        )

    assertEquals(expectedVirtualWorkspace, scoutedVirtualWorkspace)
  }

  class FakeLocalFilterLoader(
      private val localFilterByMarker: Map<Marker, GitWorktreeFilter>,
  ) : LocalFilterLoader {
    interface Marker {
      val id: String
    }

    companion object {
      val markerName = UnixPath.Name.Literal(".filter")

      fun inputEntry(marker: Marker): Pair<UnixPath.Name.Literal, ImmutableCompatFsFile> =
          markerName to ImmutableCompatFsFile(content = marker.id)

      suspend fun expectedEntry(
          marker: Marker,
      ): Pair<UnixPath.Name.Literal, CodeVirtualWorkspace.OpenedCodeFile> {
        check(marker is MyMarker)

        return markerName to
            CodeVirtualWorkspace.OpenedCodeFile(
                vcsStatus = VcsStatus.Included,
                document = markerDocument(marker),
            )
      }
    }

    override suspend fun loadLocalFilter(
        fsDirectory: ReadonlyCompatFsDirectory,
    ): GitWorktreeFilter {
      val markerFile =
          fsDirectory.extract(name = markerName) as? ReadonlyCompatFsFile
              ?: throw UnsupportedOperationException(
                  "Expected marker file '${markerName.name}' not found in the directory",
              )

      val markerId = markerFile.readText()

      val localFilterEntry =
          localFilterByMarker.entries.single { (marker, _) -> marker.id == markerId }

      return localFilterEntry.value
    }
  }
}
