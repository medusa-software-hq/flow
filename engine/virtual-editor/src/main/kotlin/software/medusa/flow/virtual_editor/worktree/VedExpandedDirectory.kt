package software.medusa.flow.virtual_editor.worktree

import software.medusa.commons.git.worktree.GitWorktreeDirectory
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.resolve
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName

data class VedExpandedDirectory(
    val labeledEntityByName: Map<UfsName.Literal, LabeledEntity>,
) : VedDirectory() {
  data class LabeledEntity(
      val status: GitWorktreeEntity.Status,
      val entity: VedEntity,
  )

  companion object {
    suspend fun load(
        sourceDirectory: GitWorktreeDirectory,
        childEntityLoader: Loader,
    ): VedExpandedDirectory {
      val directoryIndex = sourceDirectory.readIndex()

      return VedExpandedDirectory(
          labeledEntityByName =
              directoryIndex.childEntityByName.mapValues { (_, sourceEntity) ->
                LabeledEntity(
                    status = sourceEntity.status,
                    entity = childEntityLoader.load(sourceEntity = sourceEntity),
                )
              },
      )
    }
  }

  override fun visitOpenedFiles(
      directoryPath: UfsLiteralAbsolutePath,
  ): Sequence<VedOpenedFile.Visited> =
      labeledEntityByName.asSequence().flatMap { (childName, labeledEntity) ->
        val childPath = directoryPath.resolve(name = childName)

        when (val childEntity = labeledEntity.entity) {
          is VedFile -> listOfNotNull(childEntity.visitOpenedFile(childPath)).asSequence()
          is VedDirectory -> childEntity.visitOpenedFiles(directoryPath = childPath)
        }
      }
}
