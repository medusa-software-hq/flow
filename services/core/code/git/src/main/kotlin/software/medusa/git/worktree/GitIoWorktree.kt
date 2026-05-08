package software.medusa.git.worktree

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

internal class GitIoWorktreeDirectory(
    private val directoryPath: Path,
) : GitWorktreeDirectory() {
  companion object {
    private fun wrap(
        existingPath: Path,
    ): GitWorktreeNode =
        when {
          Files.isDirectory(existingPath) -> GitIoWorktreeDirectory(directoryPath = existingPath)
          else -> GitIoWorktreeFile(filePath = existingPath)
        }
  }

  init {
    require(directoryPath.isAbsolute) { "Expected absolute directory path, got: $directoryPath" }
    require(directoryPath.isDirectory()) { "Expected directory path, got: $directoryPath" }
  }

  override fun read(name: String): GitWorktreeNode? {
    val childPath = directoryPath.resolve(name)

    return when {
      !Files.exists(childPath) -> null
      else -> wrap(existingPath = childPath)
    }
  }

  override val entries: Sequence<Entry>
    get() =
        directoryPath.listDirectoryEntries().asSequence().map { childPath ->
          val childName = childPath.name

          Entry(
              name = childName,
              node = wrap(existingPath = childPath),
          )
        }
}

internal class GitIoWorktreeFile(
    private val filePath: Path,
) : GitWorktreeFile() {
  init {
    require(filePath.isAbsolute) { "Expected absolute file path, got: $filePath" }
    require(Files.isRegularFile(filePath)) { "Expected regular file path, got: $filePath" }
  }

  override fun read(): InputStream = Files.newInputStream(filePath)

  override fun isExecutable(): Boolean = filePath.isExecutable()
}
