package software.medusa.git.tree

import java.io.InputStream
import software.medusa.git.GitFileMode
import software.medusa.git.utils.checkIfNotEmpty
import software.medusa.git.worktree.GitWorktreeDirectory
import software.medusa.git.worktree.GitWorktreeFile
import software.medusa.git.worktree.GitWorktreeNode
import software.medusa.git.worktree.GitWorktreeSymlink

internal class GitWorktreeTreeGroup
private constructor(override val childEntries: Sequence<GitTreeGroup.ChildEntry>) : GitTreeGroup() {
  companion object {
    fun interpret(
        worktreeDirectory: GitWorktreeDirectory,
    ): GitWorktreeTreeGroup? {
      val childEntries =
          worktreeDirectory.entries.mapNotNull { (name, worktreeNode) ->
            val childNode: GitTreeNode =
                GitWorktreeTreeUtils.interpret(
                    worktreeNode = worktreeNode,
                ) ?: return@mapNotNull null

            ChildEntry(
                name = name,
                child = childNode,
            )
          }

      return when {
        // This check might involve recursive computations in a corner case when a filtered
        // directory where the first non-ignored file is deeply nested.
        childEntries.checkIfNotEmpty() ->
            GitWorktreeTreeGroup(
                childEntries = childEntries,
            )

        else -> null
      }
    }
  }
}

internal class GitWorktreeTreeFile private constructor(private val worktreeFile: GitWorktreeFile) :
    GitTreeFile() {
  companion object {
    fun interpret(
        worktreeNode: GitWorktreeFile,
    ): GitWorktreeTreeFile =
        GitWorktreeTreeFile(
            worktreeFile = worktreeNode,
        )
  }

  override val mode: GitFileMode
    get() =
        when {
          worktreeFile.isExecutable() -> GitFileMode.Executable
          else -> GitFileMode.Regular
        }

  override fun read(): InputStream = worktreeFile.read()
}

private object GitWorktreeTreeUtils {
  fun interpret(
      worktreeNode: GitWorktreeNode,
  ): GitTreeNode? =
      when (worktreeNode) {
        is GitWorktreeDirectory -> {
          GitWorktreeTreeGroup.interpret(
              worktreeDirectory = worktreeNode,
          )
        }

        is GitWorktreeFile -> {
          GitWorktreeTreeFile.interpret(
              worktreeNode = worktreeNode,
          )
        }

        is GitWorktreeSymlink ->
            GitTreeSymlink(
                targetPath = worktreeNode.targetPath,
            )
      }
}
