package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.paths.UnixPath

private fun GitWorktreeFilter.Companion.parse(
    gitignoreText: String,
): GitWorktreeFilter =
    parse(
        gitignoreInputStream = ByteArrayInputStream(gitignoreText.toByteArray()),
    )

class GitWorktreeEntityTests {
  @Test
  fun considerateDirectoryAppliesBaseFilterAndLocalGitignoreToFilteredView() = runBlocking {
    val worktree =
        GitConsiderateWorktreeDirectory.consider(
            fsDirectory =
                TestGitWorktreeDirectory(
                    childByName =
                        mapOf(
                            ".gitignore" to
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
                                            ".gitignore" to
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
                ),
            baseFilter = GitWorktreeFilter.parse("*.log\n"),
        )

    assertEquals(
        expected = GitWorktreeEntity.Status.Considered(GitWorktreeFilter.Classification.Include),
        actual = worktree.status,
    )

    val filteredRoot = checkNotNull(worktree.asFilteredFsEntity)

    assertNotNull(filteredRoot.extract(UnixPath.Name.Literal("keep.log")))
    assertNull(filteredRoot.extract(UnixPath.Name.Literal("drop.log")))
    assertNull(filteredRoot.extract(UnixPath.Name.Literal("local-ignore.txt")))

    assertEquals(
        expected = setOf(".gitignore", "keep.log", "keep.txt", "sub"),
        actual = filteredRoot.listEntries().map { it.name.name }.toSet(),
    )

    val filteredSubdirectory =
        assertIs<ReadonlyCompatFsDirectory>(
            filteredRoot.extract(UnixPath.Name.Literal("sub")),
        )

    assertNull(filteredSubdirectory.extract(UnixPath.Name.Literal("nested-ignore.txt")))
    assertNotNull(filteredSubdirectory.extract(UnixPath.Name.Literal("nested-keep.txt")))
    assertEquals(
        expected = setOf(".gitignore", "nested-keep.txt"),
        actual = filteredSubdirectory.listEntries().map { it.name.name }.toSet(),
    )
  }

  @Test
  fun ignoredDirectoryCanStillBeTraversedAsNonConsidered() = runBlocking {
    val root =
        GitConsiderateWorktreeDirectory.consider(
            fsDirectory =
                TestGitWorktreeDirectory(
                    childByName =
                        mapOf(
                            "build" to
                                TestGitWorktreeDirectory(
                                    childByName =
                                        mapOf(
                                            "artifact.txt" to TestGitWorktreeFile(content = "artifact"),
                                        ),
                                ),
                        ),
                ),
            baseFilter = GitWorktreeFilter.parse("build/\n"),
        )

    val buildDirectory =
        assertIs<GitInconsiderateWorktreeDirectory>(
            root.readChild(UnixPath.Name.Literal("build")),
        )

    assertEquals(
        expected = GitWorktreeEntity.Status.Considered(GitWorktreeFilter.Classification.Ignore),
        actual = buildDirectory.status,
    )
    assertNull(buildDirectory.asFilteredFsEntity)

    val artifactFile =
        assertIs<GitWorktreeFile>(
            buildDirectory.readChild(UnixPath.Name.Literal("artifact.txt")),
        )

    assertEquals(
        expected = GitWorktreeEntity.Status.NonConsidered,
        actual = artifactFile.status,
    )
    assertNull(artifactFile.asFilteredFsEntity)
  }

  @Test
  fun filteredViewKeepsDirectoriesEvenWhenAllChildrenAreFilteredOut() = runBlocking {
    val worktree =
        GitConsiderateWorktreeDirectory.consider(
            fsDirectory =
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
                ),
            baseFilter = GitWorktreeFilter.parse("empty-dir/ignored.txt\n"),
        )

    val filteredRoot = checkNotNull(worktree.asFilteredFsEntity)
    val filteredDirectory =
        assertIs<ReadonlyCompatFsDirectory>(
            filteredRoot.extract(UnixPath.Name.Literal("empty-dir")),
        )

    assertEquals(setOf("empty-dir"), filteredRoot.listEntries().map { it.name.name }.toSet())
    assertEquals(emptyList(), filteredDirectory.listEntries())
  }
}
