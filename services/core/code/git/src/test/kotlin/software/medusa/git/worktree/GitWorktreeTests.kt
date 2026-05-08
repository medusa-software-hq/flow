package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun GitWorktreeFilter.Companion.parse(
    gitignoreText: String,
): GitWorktreeFilter =
    parse(
        gitignoreInputStream = ByteArrayInputStream(gitignoreText.toByteArray()),
    )

class GitWorktreeTests {
  @Test
  fun filteredAppliesBaseFilterAndLocalGitignoreToReadAndEntries() {
    val filteredRoot =
        TestGitWorktreeDirectory(
                childByName =
                    mapOf(
                        GitWorktreeDirectory.GitignoreFileName to
                            TestGitWorktreeFile(
                                content = "!keep.log\nlocal-ignore.txt\n",
                            ),
                        "keep.log" to TestGitWorktreeFile(content = "keep"),
                        "drop.log" to TestGitWorktreeFile(content = "drop"),
                        "local-ignore.txt" to TestGitWorktreeFile(content = "ignored"),
                        "keep.txt" to TestGitWorktreeFile(content = "keep-txt"),
                        "sub" to
                            TestGitWorktreeDirectory(
                                childByName =
                                    mapOf(
                                        GitWorktreeDirectory.GitignoreFileName to
                                            TestGitWorktreeFile(
                                                content = "nested-ignore.txt\n",
                                            ),
                                        "nested-ignore.txt" to
                                            TestGitWorktreeFile(content = "ignored"),
                                        "nested-keep.txt" to
                                            TestGitWorktreeFile(content = "nested-keep"),
                                    ),
                            ),
                    ),
            )
            .filtered(
                baseFilter = GitWorktreeFilter.parse("*.log\n"),
            )

    assertNotNull(filteredRoot.read("keep.log"))
    assertNull(filteredRoot.read("drop.log"))
    assertNull(filteredRoot.read("local-ignore.txt"))

    assertEquals(
        expected = setOf(GitWorktreeDirectory.GitignoreFileName, "keep.log", "keep.txt", "sub"),
        actual = filteredRoot.entries.map { it.name }.toSet(),
    )

    val filteredSubdirectory = assertIs<GitWorktreeDirectory>(filteredRoot.read("sub"))

    assertNull(filteredSubdirectory.read("nested-ignore.txt"))
    assertNotNull(filteredSubdirectory.read("nested-keep.txt"))
    assertEquals(
        expected = setOf(GitWorktreeDirectory.GitignoreFileName, "nested-keep.txt"),
        actual = filteredSubdirectory.entries.map { it.name }.toSet(),
    )
  }

  @Test
  fun filteredKeepsDirectoriesEvenWhenAllChildrenAreFilteredOut() {
    val filteredRoot =
        TestGitWorktreeDirectory(
                childByName =
                    mapOf(
                        "empty-dir" to
                            TestGitWorktreeDirectory(
                                childByName =
                                    mapOf(
                                        "ignored.txt" to TestGitWorktreeFile(content = "ignored"),
                                    ),
                            ),
                    ),
            )
            .filtered(
                baseFilter = GitWorktreeFilter.parse("empty-dir/ignored.txt\n"),
            )

    val filteredDirectory = assertIs<GitWorktreeDirectory>(filteredRoot.read("empty-dir"))

    assertEquals(setOf("empty-dir"), filteredRoot.entries.map { it.name }.toSet())
    assertEquals(emptyList(), filteredDirectory.entries.toList())
  }
}
