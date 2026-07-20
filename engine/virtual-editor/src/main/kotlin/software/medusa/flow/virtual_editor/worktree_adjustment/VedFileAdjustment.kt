package software.medusa.flow.virtual_editor.worktree_adjustment

import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.git.worktree.GitWorktreeFile
import software.medusa.commons.text.TxtFileContent
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

sealed class VedFileAdjustment : VedEntityAdjustment() {
  typealias FileAdjustmentApplicationResult = AdjustmentResult<VedFile>

  override suspend fun adjustEntity(
      gitEntity: GitWorktreeEntity,
      editorEntity: VedEntity,
      timestamp: VedTimestamp,
  ): FileAdjustmentApplicationResult {
    val gitFile =
        gitEntity as? GitWorktreeFile
            ?: error("Expected GitWorktreeFile, got ${gitEntity::class.simpleName}")

    val editorFile =
        editorEntity as? VedFile ?: error("Expected VedFile, got ${editorEntity::class.simpleName}")

    return adjustFile(
        gitFile = gitFile,
        editorFile = editorFile,
        timestamp = timestamp,
    )
  }

  data object Open : VedFileAdjustment() {
    override suspend fun adjustFile(
        gitFile: GitWorktreeFile,
        editorFile: VedFile,
        timestamp: VedTimestamp,
    ): FileAdjustmentApplicationResult {
      val byteContent = gitFile.asFilesystemEntity.read()

      val textContent =
          TxtFileContent.decode(
              byteContent = byteContent,
          ) ?: error("Failed to decode file content as text")

      val openedFile =
          VedOpenedFile.of(
              content = textContent,
              timestamp = timestamp,
          )

      return FileAdjustmentApplicationResult(
          adjustedEntity = openedFile,
      )
    }
  }

  /**
   * Puts an opened file on the leader's board. Purely a state toggle — it reads no filesystem
   * content ([gitFile] is unused). Exposing a file that is not open is a validation error: exposure
   * is a strict sub-visibility of openness (open ⊇ exposed).
   */
  data object Expose : VedFileAdjustment() {
    override suspend fun adjustFile(
        gitFile: GitWorktreeFile,
        editorFile: VedFile,
        timestamp: VedTimestamp,
    ): FileAdjustmentApplicationResult = editorFile.reExpose(VedExposure.Exposed)
  }

  /** Takes an opened file off the leader's board. Same mechanics and constraint as [Expose]. */
  data object Hide : VedFileAdjustment() {
    override suspend fun adjustFile(
        gitFile: GitWorktreeFile,
        editorFile: VedFile,
        timestamp: VedTimestamp,
    ): FileAdjustmentApplicationResult = editorFile.reExpose(VedExposure.Hidden)
  }

  protected fun VedFile.reExpose(
      exposure: VedExposure,
  ): FileAdjustmentApplicationResult {
    val openedFile =
        this as? VedOpenedFile
            ?: error("Cannot set exposure on a file that is not open (${this::class.simpleName})")

    return FileAdjustmentApplicationResult(
        adjustedEntity = openedFile.withExposure(exposure),
    )
  }

  protected abstract suspend fun adjustFile(
      gitFile: GitWorktreeFile,
      editorFile: VedFile,
      timestamp: VedTimestamp,
  ): FileAdjustmentApplicationResult
}
