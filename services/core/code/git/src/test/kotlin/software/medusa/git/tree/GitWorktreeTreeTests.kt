package software.medusa.git.tree

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import software.medusa.git.GitFileMode
import software.medusa.git.worktree.GitConsiderateWorktreeDirectory
import software.medusa.git.worktree.GitWorktreeFilter
import software.medusa.git.worktree.TestGitWorktreeDirectory
import software.medusa.git.worktree.TestGitWorktreeFile

class GitWorktreeTreeTests {
  @Test
  fun projectsFilesystemViewIntoTreeNodes() {
    val worktree =
        kotlinx.coroutines.runBlocking {
          GitConsiderateWorktreeDirectory.consider(
                  fsDirectory =
                      TestGitWorktreeDirectory(
                          mapOf(
                              "dir" to
                                  TestGitWorktreeDirectory(
                                      childByName =
                                          mapOf(
                                              "nested.txt" to
                                                  TestGitWorktreeFile(
                                                      content = "hello",
                                                  ),
                                          ),
                                  ),
                              "script.sh" to
                                  TestGitWorktreeFile(
                                      content = "echo hi",
                                      executable = true,
                                  ),
                          ),
                      ),
                  baseFilter = GitWorktreeFilter.Passive,
              )
              .asFilteredFsEntity
        }

    val projectedGroup =
        assertNotNull(
            GitWorktreeTreeGroup.interpret(
                worktreeDirectory = checkNotNull(worktree),
            ),
        )

    val dirGroup =
        assertIs<GitWorktreeTreeGroup>(
            projectedGroup.childByName.getValue("dir"),
        )

    val nestedFile =
        assertIs<GitWorktreeTreeFile>(
            dirGroup.childByName.getValue("nested.txt"),
        )

    assertEquals(
        expected = GitFileMode.Regular,
        actual = nestedFile.mode,
    )

    assertEquals(
        expected = "hello",
        actual = nestedFile.read().readAllText(),
    )

    val scriptFile =
        assertIs<GitWorktreeTreeFile>(
            projectedGroup.childByName.getValue("script.sh"),
        )

    assertEquals(
        expected = GitFileMode.Executable,
        actual = scriptFile.mode,
    )

    assertEquals(
        expected = "echo hi",
        actual = scriptFile.read().readAllText(),
    )
  }

  @Test
  fun returnsNullForEmptyProjectedDirectory() {
    val group =
        GitWorktreeTreeGroup.interpret(
            worktreeDirectory = TestGitWorktreeDirectory(emptyMap()),
        )

    assertNull(group)
  }

  private fun InputStream.readAllText(): String = bufferedReader().readText()
}
