package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath

enum class GitFsNodeKind {
  Directory,
  File,
}

data object GitEmptyFsDirectory : ReadonlyCompatFsDirectory {
  override suspend fun listEntries(): List<Entry<*>> = emptyList()

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): ReadonlyCompatFsEntity? = null
}

suspend fun ReadonlyCompatFsDirectory.filtered(
    baseFilter: GitWorktreeFilter,
): ReadonlyCompatFsDirectory =
    GitFilteredFsDirectory.construct(
        sourceDirectory = this,
        baseFilter = baseFilter,
    )

private class GitFilteredFsDirectory
private constructor(
    private val sourceDirectory: ReadonlyCompatFsDirectory,
    private val effectiveFilter: GitWorktreeFilter,
) : ReadonlyCompatFsDirectory {
  companion object {
    private val GitignoreFileName = UnixPath.Name.Literal(".gitignore")

    suspend fun construct(
        sourceDirectory: ReadonlyCompatFsDirectory,
        baseFilter: GitWorktreeFilter,
    ): GitFilteredFsDirectory {
      val gitignoreFile =
          sourceDirectory.extract(
              name = GitignoreFileName,
          )

      val effectiveFilter =
          when (gitignoreFile) {
            is ReadonlyCompatFsFile -> {
              val localFilter =
                  GitWorktreeFilter.parse(
                      gitignoreInputStream =
                          ByteArrayInputStream(gitignoreFile.read().toByteArray()),
                  )

              localFilter.chain(baseFilter = baseFilter)
            }

            null -> baseFilter

            else ->
                throw IllegalStateException(
                    "Expected $GitignoreFileName to be a file, got ${gitignoreFile::class.simpleName}",
                )
          }

      return GitFilteredFsDirectory(
          sourceDirectory = sourceDirectory,
          effectiveFilter = effectiveFilter,
      )
    }
  }

  override suspend fun listEntries(): List<Entry<*>> =
      sourceDirectory.listEntries().mapNotNull { originalEntry ->
        val filteredEntity =
            originalEntry.entity.filtered(
                name = originalEntry.name,
                effectiveFilter = effectiveFilter,
            ) ?: return@mapNotNull null

        Entry(
            name = originalEntry.name,
            entity = filteredEntity,
        )
      }

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): ReadonlyCompatFsEntity? {
    val originalEntity = sourceDirectory.extract(name) ?: return null

    return originalEntity.filtered(
        name = name,
        effectiveFilter = effectiveFilter,
    )
  }
}

private suspend fun ReadonlyCompatFsEntity.filtered(
    name: UnixPath.Name.Literal,
    effectiveFilter: GitWorktreeFilter,
): ReadonlyCompatFsEntity? {
  val filterClassification =
      effectiveFilter.classifyEffectively(
          path = RelativeUnixPath.of(name),
          nodeKind = fsNodeKind,
      )

  return when (filterClassification) {
    GitWorktreeFilter.Classification.Ignore -> null

    GitWorktreeFilter.Classification.Include -> {
      when (this) {
        is ReadonlyCompatFsDirectory -> {
          filtered(
              baseFilter =
                  effectiveFilter.nest(
                      directoryName = name.name,
                  ),
          )
        }

        else -> this
      }
    }
  }
}

private val ReadonlyCompatFsEntity.fsNodeKind: GitFsNodeKind
  get() =
      when (this) {
        is ReadonlyCompatFsDirectory -> GitFsNodeKind.Directory
        is ReadonlyCompatFsFile -> GitFsNodeKind.File
      }
