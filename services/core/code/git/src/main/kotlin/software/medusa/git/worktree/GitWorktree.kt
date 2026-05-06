package software.medusa.git.worktree

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.isDirectory
import software.medusa.git.UnixPath

@JvmInline
value class GitWorktree(
    val rootDirectory: GitWorktreeDirectory,
) {
  companion object {
    val Empty =
        GitWorktree(
            rootDirectory = GitWorktreeDirectory.Empty,
        )

    fun read(
        worktreePath: Path,
    ): GitWorktree =
        GitWorktree(
            rootDirectory =
                GitIoWorktreeDirectory(
                    directoryPath = worktreePath,
                ),
        )
  }

  fun write(
      worktreePath: Path,
  ) {
    require(worktreePath.isDirectory()) {
      "Expected directory path to write GitWorktree to, got: $worktreePath"
    }

    rootDirectory.writeDirectory(
        directoryPath = worktreePath,
    )
  }

  fun filtered(
      globalFilter: GitWorktreeFilter,
  ): GitWorktree =
      GitWorktree(
          rootDirectory = rootDirectory.filtered(baseFilter = globalFilter),
      )
}

sealed class GitWorktreeNode {
  enum class Kind {
    Directory,
    File,
  }

  abstract val kind: Kind

  internal fun filter(
      name: String,
      effectiveFilter: GitWorktreeFilter,
  ): GitWorktreeNode? {
    val filterClassification =
        effectiveFilter.classifyEffectively(
            path = UnixPath.Relative.of(name),
            nodeKind = kind,
        )

    return when (filterClassification) {
      GitWorktreeFilter.Classification.Ignore -> null

      GitWorktreeFilter.Classification.Include -> {
        when (this) {
          is GitWorktreeDirectory ->
              filtered(
                  baseFilter =
                      effectiveFilter.nest(
                          directoryName = name,
                      ),
              )

          else -> this
        }
      }
    }
  }
}

abstract class GitWorktreeDirectory : GitWorktreeNode() {
  companion object {
    const val GitignoreFileName = ".gitignore"
  }

  data object Empty : GitWorktreeDirectory() {
    override fun read(name: String): GitWorktreeNode? = null

    override val entries: Sequence<Entry> = emptySequence()
  }

  data class Entry(
      val name: String,
      val node: GitWorktreeNode,
  )

  final override val kind: Kind
    get() = Kind.Directory

  fun filtered(
      baseFilter: GitWorktreeFilter,
  ): GitWorktreeDirectory {
    val effectiveFilter: GitWorktreeFilter =
        when (val gitignoreFile = read(name = GitignoreFileName)) {
          is GitWorktreeFile -> {
            val localFilter = GitWorktreeFilter.parse(gitignoreFile.read())
            localFilter.chain(baseFilter = baseFilter)
          }

          else -> baseFilter
        }

    return object : GitWorktreeDirectory() {
      override fun read(name: String): GitWorktreeNode? {
        val originalNode = this@GitWorktreeDirectory.read(name) ?: return null

        return originalNode.filter(
            name = name,
            effectiveFilter = effectiveFilter,
        )
      }

      override val entries: Sequence<Entry> =
          this@GitWorktreeDirectory.entries.mapNotNull { originalEntry ->
            val childNode = originalEntry.node

            val filteredNode =
                childNode.filter(
                    name = originalEntry.name,
                    effectiveFilter = effectiveFilter,
                ) ?: return@mapNotNull null

            originalEntry.copy(
                node = filteredNode,
            )
          }
    }
  }

  abstract fun read(name: String): GitWorktreeNode?

  abstract val entries: Sequence<Entry>
}

private fun GitWorktreeDirectory.writeDirectory(
    directoryPath: Path,
) {
  entries.forEach { childEntry ->
    val childName = childEntry.name
    val childNode = childEntry.node

    val childPath = directoryPath.resolve(childName)

    when (childNode) {
      is GitWorktreeDirectory -> {
        childPath.createDirectory()
        childNode.writeDirectory(directoryPath = childPath)
      }

      is GitWorktreeFile -> {
        childNode.writeFile(filePath = childPath)
      }

      is GitWorktreeSymlink -> {
        childNode.writeSymlink(symlinkPath = childPath)
      }
    }
  }
}

abstract class GitWorktreeFile : GitWorktreeNode() {
  final override val kind: Kind
    get() = Kind.File

  abstract fun read(): InputStream

  abstract fun isExecutable(): Boolean
}

private fun GitWorktreeFile.writeFile(
    filePath: Path,
) {
  read().use { contentStream -> Files.copy(contentStream, filePath) }

  if (isExecutable()) {
    filePath.toFile().setExecutable(true)
  }
}

data class GitWorktreeSymlink(
    val targetPath: UnixPath,
) : GitWorktreeNode() {
  override val kind: Kind
    get() = Kind.File // For classification purposes, symlinks are considered files
}

private fun GitWorktreeSymlink.writeSymlink(symlinkPath: Path) {
  symlinkPath.createSymbolicLinkPointingTo(target = targetPath.toPath())
}
