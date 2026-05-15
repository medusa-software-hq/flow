package software.medusa.git.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.git.GitFileMode
import software.medusa.git.worktree.TestGitTreeFile
import software.medusa.git.worktree.TestGitTreeGroup

class GitTreeTests {
  @Test
  fun realizeProjectsTreeNodesIntoWorktreeNodes() {
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

    val worktree = tree.realize()
    val rootDirectory = worktree.rootDirectory

    val nestedDirectory =
        assertIs<software.medusa.git.worktree.GitWorktreeDirectory>(rootDirectory.read("nested"))
    val helloFile =
        assertIs<software.medusa.git.worktree.GitWorktreeFile>(nestedDirectory.read("hello.txt"))
    val toolFile =
        assertIs<software.medusa.git.worktree.GitWorktreeFile>(rootDirectory.read("tool.sh"))
    val toolLink =
        assertIs<software.medusa.git.worktree.GitWorktreeSymlink>(rootDirectory.read("tool-link"))

    assertEquals("hello", helloFile.read().bufferedReader().readText())
    assertEquals("echo hi", toolFile.read().bufferedReader().readText())
    assertEquals(true, toolFile.isExecutable())
    assertEquals(
        RelativeUnixPath.of(
            UnixPath.Name.Literal("tool.sh"),
        ),
        toolLink.targetPath,
    )
  }
}
