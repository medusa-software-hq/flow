package software.medusa.flow.virtual_editor.worktree_adjustment

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFile
import software.medusa.commons.text.TxtFileContent
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

sealed class VedFileAdjustment : VedEntityAdjustment() {
  typealias FileAdjustmentApplicationResult = AdjustmentResult<VedFile>

  override suspend fun adjustEntity(
      gitEntity: GitWorktreeEntity,
      editorEntity: VedEntity,
  ): FileAdjustmentApplicationResult {
    val gitFile =
        gitEntity as? GitWorktreeFile
            ?: error("Expected GitWorktreeFile, got ${gitEntity::class.simpleName}")

    val editorFile =
        editorEntity as? VedFile ?: error("Expected VedFile, got ${editorEntity::class.simpleName}")

    return adjustFile(
        gitFile = gitFile,
        editorFile = editorFile,
    )
  }

  data object Open : VedFileAdjustment() {
    override suspend fun adjustFile(
        gitFile: GitWorktreeFile,
        editorFile: VedFile,
    ): FileAdjustmentApplicationResult {
      val byteContent = gitFile.asFilesystemEntity.read()

      val textContent =
          TxtFileContent.decode(
              byteContent = byteContent,
          ) ?: error("Failed to decode file content as text")

      val openedFile =
          VedOpenedFile(
              content = textContent,
          )

      return FileAdjustmentApplicationResult(
          adjustedEntity = openedFile,
      )
    }
  }

  protected abstract suspend fun adjustFile(
      gitFile: GitWorktreeFile,
      editorFile: VedFile,
  ): FileAdjustmentApplicationResult
}
