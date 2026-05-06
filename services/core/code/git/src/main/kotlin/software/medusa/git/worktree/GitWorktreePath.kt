package software.medusa.git.worktree

import software.medusa.git.UnixPath

/**
 * A path to a file/directory within a Git workspace, relative either to the repository root or a
 * directory within a Git workspace.
 */
@JvmInline
@Suppress("GrazieInspection")
value class GitWorktreePath( // TODO: Nuke in favor of UnixPath.Relative
    /**
     * Path segments, in order from root to leaf. Each segment is a literal name of a file/directory
     * within the Git workspace. It's never '.' or '..'. Segments cannot be empty or contain '/'.
     */
    val path: List<String>,
) {
  companion object {
    fun of(
        vararg path: String,
    ): GitWorktreePath =
        GitWorktreePath(
            path = path.toList(),
        )
  }

  init {
    require(
        path.none {
          it == UnixPath.Dot ||
              it == UnixPath.DotDot ||
              it.isEmpty() ||
              it.contains(UnixPath.Separator)
        },
    ) {
      "GitWorktreePath segments must not be '.' or '..'. Actual path: $this"
    }
  }

  fun prepend(name: String): GitWorktreePath =
      GitWorktreePath(
          path = listOf(name) + path,
      )
}
