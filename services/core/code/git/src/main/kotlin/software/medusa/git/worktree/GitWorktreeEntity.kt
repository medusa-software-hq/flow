package software.medusa.git.worktree

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.git.worktree.GitWorktreeFilter.Classification

sealed class GitWorktreeEntity {
  sealed interface Status {
    data class Considered(
        val classification: Classification,
    ) : Status

    data object NonConsidered : Status
  }

  companion object {
    suspend fun consider(
        effectiveFilter: GitWorktreeFilter,
        name: UnixPath.Name.Literal,
        fsEntity: ReadonlyCompatFsEntity,
    ): GitWorktreeEntity {
      val classification = effectiveFilter.classifyEffectively(
          path = LiteralRelativeUnixPath.of(name),
          nodeKind = fsEntity.fsNodeKind,
      )

      val status = Status.Considered(
          classification = classification,
      )

      return when (fsEntity) {
        is ReadonlyCompatFsDirectory -> {
          when (classification) {
            Classification.Ignore ->
                GitInconsiderateWorktreeDirectory(
                    fsDirectory = fsEntity,
                    status = status,
                )

            Classification.Include ->
                GitConsiderateWorktreeDirectory.consider(
                    fsDirectory = fsEntity,
                    baseFilter = effectiveFilter.nest(name.name),
                )
          }
        }

        is ReadonlyCompatFsFile ->
            GitWorktreeFile(
                fsFile = fsEntity,
                status = status,
            )
      }
    }

    fun <FsEntityT : ReadonlyCompatFsEntity> FsEntityT.filter(status: Status): FsEntityT? =
        when (status) {
          is Status.Considered -> this.takeIf { status.classification == Classification.Include }
          Status.NonConsidered -> null
        }
  }

  abstract val status: Status

  abstract val asFilteredFsEntity: ReadonlyCompatFsEntity?
}
