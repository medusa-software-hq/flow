package software.medusa.git.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.decodeToString
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.git.GitFileMode
import software.medusa.git.worktree.TestGitTreeFile
import software.medusa.git.worktree.TestGitTreeGroup

class GitTreeTests {
  @Test
  fun realizeProjectsTreeNodesIntoFilesystemView() = runBlocking {
    val tree =
        GitProperTree(
            rootGroup =
                TestGitTreeGroup(
                    sequenceOf(
                        GitTreeGroup.ChildEntry(
                            name = "nested",
                            child =
                                TestGitTreeGroup(
                                    sequenceOf(
                                        GitTreeGroup.ChildEntry(
                                            name = "hello.txt",
                                            child = TestGitTreeFile(content = "hello"),
                                        ),
                                    ),
                                ),
                        ),
                        GitTreeGroup.ChildEntry(
                            name = "tool.sh",
                            child =
                                TestGitTreeFile(
                                    content = "echo hi",
                                    mode = GitFileMode.Executable,
                                ),
                        ),
                        GitTreeGroup.ChildEntry(
                            name = "tool-link",
                            child =
                                GitTreeSymlink(
                                    targetPath =
                                        RelativeUnixPath.of(
                                            UnixPath.Name.Literal("tool.sh"),
                                        ),
                                ),
                        ),
                    ),
                ),
        )

    val rootDirectory = tree.realize()

    val nestedDirectory =
        assertIs<ReadonlyCompatFsDirectory>(
            rootDirectory.extract(UnixPath.Name.Literal("nested")),
        )
    val helloFile =
        assertIs<ReadonlyCompatFsFile>(
            nestedDirectory.extract(UnixPath.Name.Literal("hello.txt")),
        )
    val toolFile =
        assertIs<ReadonlyCompatFsFile>(
            rootDirectory.extract(UnixPath.Name.Literal("tool.sh")),
        )

    assertEquals("hello", helloFile.read().decodeToString())
    assertEquals("echo hi", toolFile.read().decodeToString())
    assertEquals(true, toolFile.isExecutable())

    assertFailsWith<UnsupportedOperationException> {
      rootDirectory.extract(UnixPath.Name.Literal("tool-link"))
    }
  }
}
