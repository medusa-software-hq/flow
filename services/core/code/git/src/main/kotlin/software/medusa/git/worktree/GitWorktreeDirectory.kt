package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.UnixPath

sealed class GitWorktreeDirectory : GitWorktreeEntity() {
  abstract override val asFilteredFsEntity: ReadonlyCompatFsDirectory?

  abstract suspend fun readStructure(): Map<UnixPath.Name.Literal, GitWorktreeEntity>

  abstract suspend fun readChild(name: UnixPath.Name.Literal): GitWorktreeEntity?
}

class GitConsiderateWorktreeDirectory private constructor(
    private val fsDirectory: ReadonlyCompatFsDirectory,
    private val effectiveFilter: GitWorktreeFilter,
) : GitWorktreeDirectory() {
  companion object {
    private val GitignoreFileName = UnixPath.Name.Literal(".gitignore")

    suspend fun consider(
        fsDirectory: ReadonlyCompatFsDirectory,
        baseFilter: GitWorktreeFilter,
    ): GitConsiderateWorktreeDirectory {
      val gitignoreFile = fsDirectory.extract(name = GitignoreFileName)

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

      return GitConsiderateWorktreeDirectory(
          fsDirectory = fsDirectory,
          effectiveFilter = effectiveFilter,
      )
    }
  }

  override val status: Status.Considered
    get() = Status.Considered(GitWorktreeFilter.Classification.Include)

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
            consider(
                effectiveFilter = effectiveFilter,
                name = name,
                fsEntity = fsEntity,
            )
      }

  override suspend fun readChild(
      name: UnixPath.Name.Literal,
  ): GitWorktreeEntity? {
    val fsEntity = fsDirectory.extract(name) ?: return null

    return consider(
        effectiveFilter = effectiveFilter,
        name = name,
        fsEntity = fsEntity,
    )
  }
}

class GitInconsiderateWorktreeDirectory(
    private val fsDirectory: ReadonlyCompatFsDirectory,
    override val status: Status,
) : GitWorktreeDirectory() {
  companion object {
    fun wrap(
        fsEntity: ReadonlyCompatFsEntity,
    ): GitWorktreeEntity =
        when (fsEntity) {
          is ReadonlyCompatFsDirectory ->
              GitInconsiderateWorktreeDirectory(
                  fsDirectory = fsEntity,
                  status = Status.NonConsidered,
              )

          is ReadonlyCompatFsFile ->
              GitWorktreeFile(
                  fsFile = fsEntity,
                  status = Status.NonConsidered,
              )
        }
  }

  override val asFilteredFsEntity: ReadonlyCompatFsDirectory?
    get() = fsDirectory.filter(status)

  override suspend fun readStructure(): Map<UnixPath.Name.Literal, GitWorktreeEntity> =
      fsDirectory.listEntries().associate { (name, fsEntity) ->
        name to wrap(fsEntity = fsEntity)
      }

  override suspend fun readChild(
      name: UnixPath.Name.Literal,
  ): GitWorktreeEntity? {
    val fsEntity = fsDirectory.extract(name) ?: return null

    return wrap(fsEntity = fsEntity)
  }
}
