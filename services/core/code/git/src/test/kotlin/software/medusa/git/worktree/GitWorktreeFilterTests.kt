package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath

private fun GitWorktreeFilter.Companion.parse(
    gitignoreText: String,
): GitWorktreeFilter =
    parse(
        gitignoreInputStream = ByteArrayInputStream(gitignoreText.toByteArray()),
    )

class GitWorktreeFilterTests {
  @Test
  fun parsesSimpleIgnoreRules() {
    val filter = GitWorktreeFilter.parse("ignored.txt\n")

    assertEquals(
        expected = GitWorktreeFilter.Classification.Ignore,
        actual =
            filter.classify(
                path = RelativeUnixPath.of(UnixPath.Name.Literal("ignored.txt")),
                nodeKind = GitWorktreeNode.Kind.File,
            ),
    )

    assertEquals(
        expected = null,
        actual =
            filter.classify(
                path = RelativeUnixPath.of(UnixPath.Name.Literal("kept.txt")),
                nodeKind = GitWorktreeNode.Kind.File,
            ),
    )
  }

  @Test
  fun chainedFiltersPreferInnerGitignore() {
    val baseFilter = GitWorktreeFilter.parse("*.log\n")

    val localFilter =
        GitWorktreeFilter.parse(
            ByteArrayInputStream(
                "!keep.log\n".toByteArray(),
            ),
        )

    val chainedFilter = localFilter.chain(baseFilter = baseFilter)

    assertEquals(
        expected = GitWorktreeFilter.Classification.Include,
        actual =
            chainedFilter.classify(
                path = RelativeUnixPath.of(UnixPath.Name.Literal("keep.log")),
                nodeKind = GitWorktreeNode.Kind.File,
            ),
    )

    assertEquals(
        expected = GitWorktreeFilter.Classification.Ignore,
        actual =
            chainedFilter.classify(
                path = RelativeUnixPath.of(UnixPath.Name.Literal("drop.log")),
                nodeKind = GitWorktreeNode.Kind.File,
            ),
    )
  }

  @Test
  fun nestedFiltersPrependDirectoryPath() {
    val filter = GitWorktreeFilter.parse("sub/ignored.txt\n")

    val nestedFilter = filter.nest("sub")

    assertEquals(
        expected = GitWorktreeFilter.Classification.Ignore,
        actual =
            nestedFilter.classify(
                path = RelativeUnixPath.of(UnixPath.Name.Literal("ignored.txt")),
                nodeKind = GitWorktreeNode.Kind.File,
            ),
    )
  }
}
