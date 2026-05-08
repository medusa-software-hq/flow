package software.medusa.git.tree

import java.io.InputStream
import software.medusa.git.GitFileMode
import software.medusa.git.worktree.GitWorktreeDirectory
import software.medusa.git.worktree.GitWorktreeFile
import software.medusa.git.worktree.GitWorktreeNode
import software.medusa.git.worktree.GitWorktreeSymlink

internal class GitWorktreeTreeGroup
private constructor(
    private val worktreeDirectory: GitWorktreeDirectory,
) : GitTreeGroup() {
  override val childEntries: Sequence<ChildEntry>
    get() =
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

  companion object {
    fun interpret(
        worktreeDirectory: GitWorktreeDirectory,
    ): GitWorktreeTreeGroup? =
        when {
          worktreeDirectory.isEmpty() -> null
          else ->
              GitWorktreeTreeGroup(
                  worktreeDirectory = worktreeDirectory,
              )
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
