package software.medusa.git.worktree

import java.io.InputStream
import org.eclipse.jgit.ignore.IgnoreNode
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath

interface GitWorktreeFilter {
  data object Passive : GitWorktreeFilter {
    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): Classification? = null
  }

  companion object {
    fun parse(
        gitignoreInputStream: InputStream,
    ): GitWorktreeFilter {
      val ignoreNode = IgnoreNode()
      ignoreNode.parse(gitignoreInputStream)

      return object : GitWorktreeFilter {
        override fun classify(
            path: LiteralRelativeUnixPath,
            nodeKind: GitFsNodeKind,
        ): Classification? {
          val matchResult =
              ignoreNode.isIgnored(
                  path.toUnixRelativePathString(),
                  nodeKind == GitFsNodeKind.Directory,
              )

          return when (matchResult) {
            IgnoreNode.MatchResult.CHECK_PARENT -> null
            IgnoreNode.MatchResult.CHECK_PARENT_NEGATE_FIRST_MATCH -> null
            IgnoreNode.MatchResult.IGNORED -> Classification.Ignore
            IgnoreNode.MatchResult.NOT_IGNORED -> Classification.Include
          }
        }
      }
    }
  }

  enum class Classification {
    Ignore,
    Include,
  }

  fun classify(
      path: LiteralRelativeUnixPath,
      nodeKind: GitFsNodeKind,
  ): Classification?
}

fun GitWorktreeFilter.classifyEffectively(
    path: LiteralRelativeUnixPath,
    nodeKind: GitFsNodeKind,
): GitWorktreeFilter.Classification =
    classify(
        path = path,
        nodeKind = nodeKind,
    ) ?: GitWorktreeFilter.Classification.Include

fun GitWorktreeFilter.nest(
    directoryName: String,
): GitWorktreeFilter {
  val baseFilter = this@nest

  return object : GitWorktreeFilter {
    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): GitWorktreeFilter.Classification? =
        baseFilter.classify(
            path =
                RelativeUnixPath.of(
                    listOf(UnixPath.Name.Literal(directoryName)) + path.names,
                ),
            nodeKind = nodeKind,
        )
  }
}

fun GitWorktreeFilter.chain(
    baseFilter: GitWorktreeFilter,
): GitWorktreeFilter {
  val innerFilter = this@chain

  return object : GitWorktreeFilter {
    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): GitWorktreeFilter.Classification? =
        innerFilter.classify(path, nodeKind) ?: baseFilter.classify(path, nodeKind)
  }
}
