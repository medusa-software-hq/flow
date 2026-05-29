package software.medusa.code_agent.scouting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import software.medusa.code_agent.exploration.CodeFileExplorer
import software.medusa.code_agent.structure.CodeFileStructure
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.LockState
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace.VcsStatus
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.impl.immutable.ImmutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.impl.immutable.ImmutableCompatFsFile
import software.medusa.commons.filesystem.compat.readText
import software.medusa.commons.filesystem.tech.TechFileContent
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
    private fun paragraph(text: String): MarkdownBlock.Paragraph =
        MarkdownBlock.Paragraph(
            inlineContent = listOf(MarkdownInline.Text(text)),
        )

    private val binaryByteString = ByteString(0.toByte(), 1.toByte(), 2.toByte(), 3.toByte())

    private val srcDirectoryName = UnixPath.Name.Literal("src")

    private val buildDirectoryName = UnixPath.Name.Literal("build")

    private val srcProperFile1Name = UnixPath.Name.Literal("file1.src")

    private val executableFileName = UnixPath.Name.Literal("out.exe")

    private val srcProperFile1CodeBlock =
        CodeBlock.of(
            /* 01 */ "%import foo.bar",
            /* 02 */ "%import foo.baz",
            /* 03 */ "",
            /* 04 */ "%def %class Foo {",
            /* 05 */ "  %def %fun f1[x: Int] => %let {",
            /* 06 */ "    y = x * 2,",
            /* 07 */ "    z = y * 3,",
            /* 08 */ "    w = z + y,",
            /* 09 */ "  } %in w",
            /* 11 */ "  ",
            /* 13 */ "  %def %fun f2[x: Int] => %let {",
            /* 14 */ "    y = x * 3,",
            /* 15 */ "    z = y * 11,",
            /* 16 */ "    w = z + 9,",
            /* 17 */ "  } %in w",
            /* 18 */ "}",
            /* 19 */ "",
            /* 20 */ "%def %fun g1[x: Int] => %let {",
            /* 21 */ "  y = x * 31,",
            /* 22 */ "  z = y * 121,",
            /* 23 */ "  w = z + 93,",
            /* 24 */ "} %in w",
        )

    private val srcProperFile1ExplorationResult =
        CodeFileExplorer.ExplorationResult(
            fileSummary = paragraph("A useful code file with some useful code."),
            fileStructure =
                CodeFileStructure(
                    topLevelSectionStructureBySymbol =
                        mapOf(
                            CodeFileStructure.EntityNameSymbol("Foo") to
                                CodeFileStructure.SectionStructure(
                                    coveredRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(5),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(19),
                                        ),
                                    sectionSummary =
                                        paragraph(
                                            "A very useful Foo class doing useful foo things."
                                        ),
                                    nestedSectionStructureBySymbol =
                                        mapOf(
                                            CodeFileStructure.EntityNameSymbol("f1") to
                                                CodeFileStructure.SectionStructure(
                                                    coveredRange =
                                                        CodeBlock.LineIndexRange(
                                                            startIndex =
                                                                CodeBlock.LineIndex.ofOneBased(
                                                                    6,
                                                                ),
                                                            endIndexExclusive =
                                                                CodeBlock.LineIndex.ofOneBased(9),
                                                        ),
                                                    sectionSummary =
                                                        paragraph(
                                                            "A very useful f1 function computing useful results.",
                                                        ),
                                                    nestedSectionStructureBySymbol = emptyMap(),
                                                ),
                                            CodeFileStructure.EntityNameSymbol("f2") to
                                                CodeFileStructure.SectionStructure(
                                                    coveredRange =
                                                        CodeBlock.LineIndexRange(
                                                            startIndex =
                                                                CodeBlock.LineIndex.ofOneBased(
                                                                    14,
                                                                ),
                                                            endIndexExclusive =
                                                                CodeBlock.LineIndex.ofOneBased(17),
                                                        ),
                                                    sectionSummary =
                                                        paragraph(
                                                            "A very useful f2 function computing even more useful results.",
                                                        ),
                                                    nestedSectionStructureBySymbol = emptyMap(),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    private val srcProperFile2Name = UnixPath.Name.Literal("file2.src")

    private val srcProperFile2CodeBlock =
        CodeBlock.of(
            /* 01 */ "%import foo.bar",
            /* 03 */ "",
            /* 04 */ "%def %class Foo2 {",
            /* 05 */ "  %def %fun f1[x: Int] => %let {",
            /* 06 */ "    y = x * 2,",
            /* 07 */ "    z = y * 3,",
            /* 08 */ "  } %in w",
            /* 09 */ "}",
        )

    private val srcProperFile2ExplorationResult =
        CodeFileExplorer.ExplorationResult(
            fileSummary = paragraph("A second useful code file with some useful code."),
            fileStructure =
                CodeFileStructure(
                    topLevelSectionStructureBySymbol =
                        mapOf(
                            CodeFileStructure.EntityNameSymbol("Foo2") to
                                CodeFileStructure.SectionStructure(
                                    coveredRange =
                                        CodeBlock.LineIndexRange(
                                            startIndex = CodeBlock.LineIndex.ofOneBased(5),
                                            endIndexExclusive = CodeBlock.LineIndex.ofOneBased(10),
                                        ),
                                    sectionSummary =
                                        paragraph(
                                            "A very useful Foo2 class doing useful foo things."
                                        ),
                                    nestedSectionStructureBySymbol =
                                        mapOf(
                                            CodeFileStructure.EntityNameSymbol("f1") to
                                                CodeFileStructure.SectionStructure(
                                                    coveredRange =
                                                        CodeBlock.LineIndexRange(
                                                            startIndex =
                                                                CodeBlock.LineIndex.ofOneBased(
                                                                    6,
                                                                ),
                                                            endIndexExclusive =
                                                                CodeBlock.LineIndex.ofOneBased(9),
                                                        ),
                                                    sectionSummary =
                                                        paragraph(
                                                            "A very useful f1 function computing useful results.",
                                                        ),
                                                    nestedSectionStructureBySymbol = emptyMap(),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    private val srcTrashFileName = UnixPath.Name.Literal("trash.txt")

    private val srcTrashFileCodeBlock =
        CodeBlock.of(
            "trash",
            "more trash",
            "trash",
            "trash",
        )

    private val srcTrashFileExplorationResult =
        CodeFileExplorer.ExplorationResult(
            fileSummary = paragraph("A trash code file with useless code."),
            fileStructure =
                CodeFileStructure(
                    topLevelSectionStructureBySymbol = emptyMap(),
                ),
        )

    private val markerFileExplorationResult =
        CodeFileExplorer.ExplorationResult(
            fileSummary = paragraph("A helpful marker."),
            fileStructure =
                CodeFileStructure(
                    topLevelSectionStructureBySymbol = emptyMap(),
                ),
        )
  }

  private data object FakeFileExplorer : CodeFileExplorer {
    override suspend fun exploreFile(
        fileName: UnixPath.Name.Literal,
        fileContent: TechFileContent.Code,
    ): CodeFileExplorer.ExplorationResult =
        when {
          fileName == FakeLocalFilterLoader.markerName -> markerFileExplorationResult
          else ->
              when (fileContent.code) {
                srcProperFile1CodeBlock -> srcProperFile1ExplorationResult
                srcProperFile2CodeBlock -> srcProperFile2ExplorationResult
                srcTrashFileCodeBlock -> srcTrashFileExplorationResult
                else ->
                    throw UnsupportedOperationException(
                        "Unexpected file content passed to the structure extractor in the test",
                    )
              }
        }
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
                                                            content = binaryByteString,
                                                        ),
                                                ),
                                        ),
                                    srcProperFile1Name to
                                        ImmutableCompatFsFile(
                                            content = srcProperFile1CodeBlock.dump(),
                                        ),
                                    srcProperFile2Name to
                                        ImmutableCompatFsFile(
                                            content = srcProperFile2CodeBlock.dump(),
                                        ),
                                    srcTrashFileName to
                                        ImmutableCompatFsFile(
                                            content = srcTrashFileCodeBlock.dump(),
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
            structureExtractor = FakeFileExplorer,
        )

    val scoutedVirtualWorkspace =
        worktreeScout.preScoutWorktree(
            gitWorktree =
                GitWorktree(
                    rootDirectory = rootGitWorktreeDirectory,
                ),
        )

    val expectedSrcDirectory =
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
                            structuredContent =
                                CodeVirtualWorkspace.OpenedCodeFile.StructuredContent(
                                    content =
                                        TechFileContent.Code(
                                            code = srcProperFile1CodeBlock,
                                        ),
                                    structure = srcProperFile1ExplorationResult.fileStructure,
                                ),
                        ),
                    srcProperFile2Name to
                        CodeVirtualWorkspace.OpenedCodeFile(
                            vcsStatus = VcsStatus.Included,
                            structuredContent =
                                CodeVirtualWorkspace.OpenedCodeFile.StructuredContent(
                                    content =
                                        TechFileContent.Code(
                                            code = srcProperFile2CodeBlock,
                                        ),
                                    structure = srcProperFile2ExplorationResult.fileStructure,
                                ),
                        ),
                    srcTrashFileName to
                        CodeVirtualWorkspace.OpenedCodeFile(
                            vcsStatus = VcsStatus.Ignored,
                            structuredContent =
                                CodeVirtualWorkspace.OpenedCodeFile.StructuredContent(
                                    content =
                                        TechFileContent.Code(
                                            code = srcTrashFileCodeBlock,
                                        ),
                                    structure = srcTrashFileExplorationResult.fileStructure,
                                ),
                        ),
                ),
        )

    val expectedVirtualWorkspace =
        CodeVirtualWorkspace(
            rootDirectory =
                CodeVirtualWorkspace.ExpandedDirectory(
                    childEntityByName =
                        mapOf(
                            FakeLocalFilterLoader.expectedEntry(MyMarker.Root),
                            srcDirectoryName to expectedSrcDirectory,
                            buildDirectoryName to
                                CodeVirtualWorkspace.CollapsedDirectory(
                                    vcsStatus = VcsStatus.Ignored,
                                    lockState = LockState.Unlocked,
                                ),
                        ),
                ),
        )

    assertEquals(
        expected = expectedVirtualWorkspace,
        actual = scoutedVirtualWorkspace,
    )
  }

  class FakeLocalFilterLoader(
      private val localFilterByMarker: Map<Marker, GitWorktreeFilter>,
  ) : LocalFilterLoader {
    interface Marker {
      val id: String
    }

    companion object {
      val markerName = UnixPath.Name.Literal(".filter")

      fun inputEntry(
          marker: Marker,
      ): Pair<UnixPath.Name.Literal, ImmutableCompatFsFile> =
          markerName to ImmutableCompatFsFile(content = marker.id)

      fun expectedEntry(
          marker: Marker,
      ): Pair<UnixPath.Name.Literal, CodeVirtualWorkspace.OpenedCodeFile> =
          markerName to
              CodeVirtualWorkspace.OpenedCodeFile(
                  vcsStatus = VcsStatus.Included,
                  structuredContent =
                      CodeVirtualWorkspace.OpenedCodeFile.StructuredContent(
                          content =
                              TechFileContent.Code(
                                  code = CodeBlock.of(marker.id),
                              ),
                          structure = markerFileExplorationResult.fileStructure,
                      ),
              )
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
