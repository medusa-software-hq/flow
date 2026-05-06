package software.medusa.git.worktree

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.git.GitFileMode
import software.medusa.git.UnixPath
import software.medusa.git.tree.GitProperTree

class GitWorktreeWriteTests {
  @Test
  fun writeMaterializesRealizedTreeToFilesystem() {
    val outputDirectory = Files.createTempDirectory("git-worktree-write-")
    val worktree =
        GitProperTree(
                rootGroup =
                    TestGitTreeGroup(
                        sequenceOf(
                            software.medusa.git.tree.GitTreeGroup.ChildEntry(
                                name = "nested",
                                child =
                                    TestGitTreeGroup(
                                        sequenceOf(
                                            software.medusa.git.tree.GitTreeGroup.ChildEntry(
                                                name = "hello.txt",
                                                child = TestGitTreeFile(content = "hello"),
                                            ),
                                        ),
                                    ),
                            ),
                            software.medusa.git.tree.GitTreeGroup.ChildEntry(
                                name = "tool.sh",
                                child =
                                    TestGitTreeFile(
                                        content = "echo hi",
                                        mode = GitFileMode.Executable,
                                    ),
                            ),
                            software.medusa.git.tree.GitTreeGroup.ChildEntry(
                                name = "tool-link",
                                child =
                                    software.medusa.git.tree.GitTreeSymlink(
                                        targetPath = UnixPath.Relative.of("tool.sh"),
                                    ),
                            ),
                        ),
                    ),
            )
            .realize()

    worktree.write(outputDirectory)

    val nestedDirectory = outputDirectory.resolve("nested")
    val helloFile = nestedDirectory.resolve("hello.txt")
    val toolFile = outputDirectory.resolve("tool.sh")
    val toolLink = outputDirectory.resolve("tool-link")

    assertTrue(nestedDirectory.isDirectory())
    assertTrue(helloFile.isRegularFile())
    assertEquals("hello", helloFile.readText())

    assertTrue(toolFile.exists())
    assertEquals("echo hi", toolFile.readText())
    assertTrue(toolFile.isExecutable())

    assertTrue(toolLink.isSymbolicLink())
    assertEquals(UnixPath.Relative.of("tool.sh").toPath(), toolLink.readSymbolicLink())
  }

  @Test
  fun writeCopiesInMemoryWorktreeToFilesystem() {
    val outputDirectory = Files.createTempDirectory("git-worktree-write-manual-")
    val worktree =
        GitWorktree(
            rootDirectory =
                TestGitWorktreeDirectory(
                    childByName =
                        mapOf(
                            "dir" to
                                TestGitWorktreeDirectory(
                                    childByName =
                                        mapOf(
                                            "file.txt" to TestGitWorktreeFile(content = "content"),
                                        ),
                                ),
                            "script.sh" to
                                TestGitWorktreeFile(
                                    content = "echo hi",
                                    executable = true,
                                ),
                        ),
                ),
        )

    worktree.write(outputDirectory)

    assertTrue(outputDirectory.resolve("dir").isDirectory())
    assertEquals("content", outputDirectory.resolve("dir/file.txt").readText())
    assertEquals("echo hi", outputDirectory.resolve("script.sh").readText())
    assertTrue(outputDirectory.resolve("script.sh").isExecutable())
  }
}
