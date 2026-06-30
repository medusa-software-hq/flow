package software.medusa.flow.virtual_editor.worktree_adjustment

import software.medusa.commons.git.worktree.GitWorktreeDirectory
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFile
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedCollapsedDirectory
import software.medusa.flow.virtual_editor.worktree.VedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory

sealed class VedDirectoryAdjustment : VedEntityAdjustment() {
  typealias DirectoryAdjustmentApplicationResult = AdjustmentResult<VedExpandedDirectory>

  data object Expand : VedDirectoryAdjustment() {
    override suspend fun adjustDirectory(
        gitDirectory: GitWorktreeDirectory,
        editorDirectory: VedDirectory,
    ): DirectoryAdjustmentApplicationResult =
        expandDirectory(
            gitDirectory = gitDirectory,
        )

    private suspend fun expandDirectory(
        gitDirectory: GitWorktreeDirectory,
    ): DirectoryAdjustmentApplicationResult {
      val directoryIndex = gitDirectory.readIndex()

      val labeledEntityByName =
          directoryIndex.childEntityByName.mapValues { (_, gitChildEntity) ->
            VedExpandedDirectory.LabeledEntity(
                status = gitChildEntity.status,
                entity =
                    when (gitChildEntity) {
                      is GitWorktreeDirectory -> VedCollapsedDirectory
                      is GitWorktreeFile -> VedClosedFile
                    },
            )
          }

      return DirectoryAdjustmentApplicationResult(
          adjustedEntity =
              VedExpandedDirectory(
                  labeledEntityByName = labeledEntityByName,
              ),
      )
    }
  }

  data class Dive(
      val childAdjustmentByName: Map<UfsName.Literal, VedEntityAdjustment>,
  ) : VedDirectoryAdjustment() {
    init {
      require(childAdjustmentByName.isNotEmpty())
    }

    override suspend fun adjustDirectory(
        gitDirectory: GitWorktreeDirectory,
        editorDirectory: VedDirectory,
    ): DirectoryAdjustmentApplicationResult =
        when (editorDirectory) {
          is VedExpandedDirectory ->
              diveIntoDirectory(
                  gitDirectory = gitDirectory,
                  expandedDirectory = editorDirectory,
              )

          else -> error("Cannot dive into a collapsed directory")
        }

    private suspend fun diveIntoDirectory(
        gitDirectory: GitWorktreeDirectory,
        expandedDirectory: VedExpandedDirectory,
    ): DirectoryAdjustmentApplicationResult {
      val adjustedLabeledEntityByName =
          expandedDirectory.labeledEntityByName.mapValues { (name, labeledEntity) ->
            val gitChildEntity =
                gitDirectory.readChild(name = name)
                    ?: error("Child entity not found in git directory: $name")

            val childAdjustment = childAdjustmentByName[name] ?: return@mapValues labeledEntity

            val adjustedEntity =
                childAdjustment
                    .adjustEntity(
                        gitEntity = gitChildEntity,
                        editorEntity = labeledEntity.entity,
                    )
                    .adjustedEntity

            labeledEntity.copy(entity = adjustedEntity)
          }

      return DirectoryAdjustmentApplicationResult(
          adjustedEntity =
              VedExpandedDirectory(
                  labeledEntityByName = adjustedLabeledEntityByName,
              ),
      )
    }
  }

  final override suspend fun adjustEntity(
      gitEntity: GitWorktreeEntity,
      editorEntity: VedEntity,
  ): DirectoryAdjustmentApplicationResult {
    val gitDirectory =
        gitEntity as? GitWorktreeDirectory
            ?: error("Expected GitWorktreeDirectory, got ${gitEntity::class.simpleName}")

    val editorDirectory =
        editorEntity as? VedDirectory
            ?: error("Expected VedDirectory, got ${editorEntity::class.simpleName}")

    return adjustDirectory(
        gitDirectory = gitDirectory,
        editorDirectory = editorDirectory,
    )
  }

  abstract suspend fun adjustDirectory(
      gitDirectory: GitWorktreeDirectory,
      editorDirectory: VedDirectory,
  ): DirectoryAdjustmentApplicationResult
}
