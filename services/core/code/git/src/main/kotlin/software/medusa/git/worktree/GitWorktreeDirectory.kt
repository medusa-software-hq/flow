package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.UnixPath
import software.medusa.git.worktree.GitIncludedWorktreeDirectory.LocalFilterLoader
import software.medusa.git.worktree.GitWorktreeEntity.Status
import software.medusa.git.worktree.GitWorktreeFilter.Classification

sealed interface GitWorktreeDirectory : GitWorktreeEntity, ReadonlyCompatFsDirectory {
  override val asFsEntity: ReadonlyCompatFsDirectory

  override val asFilteredFsEntity: ReadonlyCompatFsDirectory?

  val effectiveFilter: GitWorktreeFilter?

  suspend fun readStructure(): Map<UnixPath.Name.Literal, GitWorktreeEntity>

  suspend fun readChild(name: UnixPath.Name.Literal): GitWorktreeEntity?
}

interface GitIncludedWorktreeDirectory : GitWorktreeDirectory {
  interface LocalFilterLoader {
    suspend fun loadLocalFilter(
        fsDirectory: ReadonlyCompatFsDirectory,
    ): GitWorktreeFilter?
  }

  data object GitignoreLocalFilterLoader : LocalFilterLoader {
    private val GitignoreFileName = UnixPath.Name.Literal(".gitignore")

    override suspend fun loadLocalFilter(
        fsDirectory: ReadonlyCompatFsDirectory,
    ): GitWorktreeFilter? {
      val gitignoreFile = fsDirectory.extract(name = GitignoreFileName) ?: return null

      if (gitignoreFile !is ReadonlyCompatFsFile) {
        throw IllegalStateException(
            "Expected $GitignoreFileName to be a file, got ${gitignoreFile::class.simpleName}",
        )
      }

      return GitWorktreeFilter.parse(
          gitignoreInputStream = ByteArrayInputStream(gitignoreFile.read().toByteArray()),
      )
    }
  }

  companion object {
    context(localFilterLoader: LocalFilterLoader)
    suspend fun include(
        fsDirectory: ReadonlyCompatFsDirectory,
        baseFilter: GitWorktreeFilter,
    ): GitIncludedWorktreeDirectory {
      val localFilter = localFilterLoader.loadLocalFilter(fsDirectory = fsDirectory)

      val effectiveFilter =
          when (localFilter) {
            null -> baseFilter
            else -> localFilter.chain(baseFilter)
          }

      return FsGitIncludedWorktreeDirectory(
          localFilterLoader = localFilterLoader,
          fsDirectory = fsDirectory,
          effectiveFilter = effectiveFilter,
      )
    }
  }

  override val status: Status.Considered

  override val asFilteredFsEntity: ReadonlyCompatFsDirectory

  override val effectiveFilter: GitWorktreeFilter
}

class FsGitIncludedWorktreeDirectory(
    private val localFilterLoader: LocalFilterLoader,
    private val fsDirectory: ReadonlyCompatFsDirectory,
    override val effectiveFilter: GitWorktreeFilter,
) : GitIncludedWorktreeDirectory, ReadonlyCompatFsDirectory by fsDirectory {

  override val status: Status.Considered
    get() = Status.Considered(Classification.Include)

  override val asFsEntity: ReadonlyCompatFsDirectory
    get() = fsDirectory

  override val asFilteredFsEntity: ReadonlyCompatFsDirectory
    get() =
        object : ReadonlyCompatFsDirectory {
          override suspend fun listEntries(): List<Entry<*>> =
              readStructure().mapNotNull { (name, childEntity) ->
                val filteredFsEntity = childEntity.asFilteredFsEntity ?: return@mapNotNull null

                Entry(
                    name = name,
                    entity = filteredFsEntity,
                )
              }

          override suspend fun extract(
              name: UnixPath.Name.Literal,
          ): ReadonlyCompatFsEntity? {
            val childEntity = readChild(name = name) ?: return null

            return childEntity.asFilteredFsEntity
          }
        }

  override suspend fun readStructure(): Map<UnixPath.Name.Literal, GitWorktreeEntity> =
      fsDirectory.listEntries().associate { (name, fsEntity) ->
        name to
            with(localFilterLoader) {
              GitWorktreeEntity.consider(
                  effectiveFilter = effectiveFilter,
                  name = name,
                  fsEntity = fsEntity,
              )
            }
      }

  override suspend fun readChild(
      name: UnixPath.Name.Literal,
  ): GitWorktreeEntity? {
    val fsEntity = fsDirectory.extract(name) ?: return null

    return with(localFilterLoader) {
      GitWorktreeEntity.consider(
          effectiveFilter = effectiveFilter,
          name = name,
          fsEntity = fsEntity,
      )
    }
  }
}

interface GitExcludedWorktreeDirectory : GitWorktreeDirectory {
  override val asFilteredFsEntity: Nothing?

  override val effectiveFilter: Nothing?
}

sealed class FsGitExcludedWorktreeDirectory(
    private val fsDirectory: ReadonlyCompatFsDirectory,
) : GitExcludedWorktreeDirectory, ReadonlyCompatFsDirectory by fsDirectory {
  override val asFsEntity: ReadonlyCompatFsDirectory
    get() = fsDirectory

  class Ignored(
      fsDirectory: ReadonlyCompatFsDirectory,
  ) : FsGitExcludedWorktreeDirectory(fsDirectory = fsDirectory) {
    override val status: Status.Considered
      get() = Status.Considered(classification = Classification.Ignore)
  }

  class NonConsidered(
      fsDirectory: ReadonlyCompatFsDirectory,
  ) : FsGitExcludedWorktreeDirectory(fsDirectory = fsDirectory) {
    override val status: Status.NonConsidered
      get() = Status.NonConsidered
  }

  companion object {
    fun wrap(
        fsEntity: ReadonlyCompatFsEntity,
    ): GitWorktreeEntity =
        when (fsEntity) {
          is ReadonlyCompatFsDirectory ->
              FsGitExcludedWorktreeDirectory.NonConsidered(
                  fsDirectory = fsEntity,
              )

          is ReadonlyCompatFsFile ->
              GitWorktreeFile(
                  fsFile = fsEntity,
                  status = Status.NonConsidered,
              )
        }
  }

  final override val asFilteredFsEntity: Nothing?
    get() = null

  final override val effectiveFilter: Nothing?
    get() = null

  final override suspend fun readStructure(): Map<UnixPath.Name.Literal, GitWorktreeEntity> =
      fsDirectory.listEntries().associate { (name, fsEntity) -> name to wrap(fsEntity = fsEntity) }

  final override suspend fun readChild(
      name: UnixPath.Name.Literal,
  ): GitWorktreeEntity? {
    val fsEntity = fsDirectory.extract(name) ?: return null

    return wrap(fsEntity = fsEntity)
  }
}
