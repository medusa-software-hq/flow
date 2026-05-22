package software.medusa.git.tree

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.git.GitFileMode

internal class GitWorktreeTreeGroup
private constructor(
    private val worktreeDirectory: ReadonlyCompatFsDirectory,
) : GitTreeGroup() {
  override val childEntries: Sequence<ChildEntry>
    get() =
        runBlocking { worktreeDirectory.listEntries() }
            .asSequence()
            .mapNotNull { entry ->
              val childNode: GitTreeNode =
                  GitWorktreeTreeUtils.interpret(
                      worktreeNode = entry.entity,
                  ) ?: return@mapNotNull null

              ChildEntry(
                  name = entry.name.name,
                  child = childNode,
              )
            }

  companion object {
    fun interpret(
        worktreeDirectory: ReadonlyCompatFsDirectory,
    ): GitWorktreeTreeGroup? =
        when {
          runBlocking { worktreeDirectory.listEntries().isEmpty() } -> null
          else ->
              GitWorktreeTreeGroup(
                  worktreeDirectory = worktreeDirectory,
              )
        }
  }
}

internal class GitWorktreeTreeFile
private constructor(private val worktreeFile: ReadonlyCompatFsFile) : GitTreeFile() {
  companion object {
    fun interpret(
        worktreeNode: ReadonlyCompatFsFile,
    ): GitWorktreeTreeFile =
        GitWorktreeTreeFile(
            worktreeFile = worktreeNode,
        )
  }

  override val mode: GitFileMode
    get() =
        when {
          runBlocking { worktreeFile.isExecutable() } -> GitFileMode.Executable
          else -> GitFileMode.Regular
        }

  override fun read(): InputStream =
      ByteArrayInputStream(runBlocking { worktreeFile.read().toByteArray() })
}

private object GitWorktreeTreeUtils {
  fun interpret(
      worktreeNode: ReadonlyCompatFsEntity,
  ): GitTreeNode? =
      when (worktreeNode) {
        is ReadonlyCompatFsDirectory -> {
          GitWorktreeTreeGroup.interpret(
              worktreeDirectory = worktreeNode,
          )
        }

        is ReadonlyCompatFsFile -> {
          GitWorktreeTreeFile.interpret(
              worktreeNode = worktreeNode,
          )
        }

        else -> null
      }
}
