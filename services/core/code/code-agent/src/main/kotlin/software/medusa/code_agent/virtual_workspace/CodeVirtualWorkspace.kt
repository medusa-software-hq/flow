package software.medusa.code_agent.virtual_workspace

import software.medusa.code_agent.virtual_workspace.document.CodeDocument
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.paths.UnixPath

data class CodeVirtualWorkspace(
    val rootDirectory: ExpandedDirectory,
) {
  enum class LockState {
    Unlocked,
    Locked,
  }

  sealed interface VcsStatus {
    data object Included : VcsStatus

    data object Ignored : VcsStatus

    data object NonConsidered : VcsStatus
  }

  // region Entity model

  sealed interface Entity {
    val vcsStatus: VcsStatus
  }

  sealed interface Directory : Entity

  data class ExpandedDirectory(
      val childEntityByName: Map<UnixPath.Name.Literal, Entity>,
  ) : Directory {
    override val vcsStatus: VcsStatus
      get() = VcsStatus.Included
  }

  data class CollapsedDirectory(
      override val vcsStatus: VcsStatus,
      val lockState: LockState,
  ) : Directory

  sealed interface File : Entity

  data class OpenedCodeFile(
      override val vcsStatus: VcsStatus,
      val document: CodeDocument,
  ) : File

  data class ClosedFile(
      override val vcsStatus: VcsStatus,
      val lockState: LockState,
  ) : File

  // endregion

  // region Reshaping model

  data class ReshapingContext(
      val foo: Unit,
  )

  @JvmInline
  value class Reshape(
      val rootDirectoryReshape: ExpandedDirectoryReshape.ReshapeRecursively,
  ) {
    fun reshape(
        workspace: CodeVirtualWorkspace,
    ): CodeVirtualWorkspace =
        CodeVirtualWorkspace(
            rootDirectory =
                rootDirectoryReshape.reshapeRecursively(
                    originalDirectory = workspace.rootDirectory,
                ),
        )
  }

  sealed interface EntityReshape

  sealed interface DirectoryReshape : EntityReshape

  sealed interface ExpandedDirectoryReshape : DirectoryReshape {
    data object Collapse : ExpandedDirectoryReshape

    @JvmInline
    value class ReshapeRecursively(
        val childReshapeByName: Map<UnixPath.Name.Literal, EntityReshape>,
    ) : ExpandedDirectoryReshape
  }

  sealed interface CollapsedDirectoryReshape : DirectoryReshape {
    data object Expand : CollapsedDirectoryReshape
  }

  sealed interface FileReshape : EntityReshape

  sealed interface OpenedFileReshape : FileReshape {
    data object Close : OpenedFileReshape

    //    @JvmInline
    //    value class CollapseSection(
    //        val chunkIndex: ChunkedCodeBlock.ChunkIndex,
    //    ) : OpenedFileReshape
    //
    //    @JvmInline
    //    value class ExpandSection(
    //        val chunkIndex: ChunkedCodeBlock.ChunkIndex,
    //    ) : OpenedFileReshape

  }

  sealed interface ClosedFileReshape : FileReshape {
    data object Open : ClosedFileReshape
  }

  // endregion

  // region Change model

  @JvmInline
  value class Change(
      val rootDirectoryModification: DirectoryModification.ChangeRecursively,
  ) {
    fun apply(
        workspace: CodeVirtualWorkspace,
    ): CodeVirtualWorkspace =
        CodeVirtualWorkspace(
            rootDirectory = TODO(),
        )
  }

  sealed interface EntityChange

  sealed interface EntityCreation : EntityChange {
    data object CreateEmptyDirectory : EntityCreation {}

    @JvmInline
    value class CreateFile(
        val initialContent: CodeBlock,
    ) : EntityCreation
  }

  sealed interface DirectoryModification : EntityChange {
    data class Delete(
        val mode: Mode,
    ) : DirectoryModification {

      enum class Mode {
        Flat,
        Recursive,
      }
    }

    @JvmInline
    value class ChangeRecursively(
        val childChangeByName: Map<UnixPath.Name.Literal, EntityChange>,
    ) : DirectoryModification
  }

  sealed interface FileModification : EntityChange {
    data object Delete : FileModification {}

    @JvmInline
    value class Update(
        val patch: Any?, // FIXME
    ) : FileModification
  }

  // endregion
}

// region Reshaping implementation

fun CodeVirtualWorkspace.EntityReshape.reshape(
    originalEntity: CodeVirtualWorkspace.Entity,
): CodeVirtualWorkspace.Entity =
    when (this) {
      is CodeVirtualWorkspace.DirectoryReshape ->
          when (originalEntity) {
            is CodeVirtualWorkspace.Directory ->
                reshapeDirectory(originalDirectory = originalEntity)
            is CodeVirtualWorkspace.File ->
                throw IllegalArgumentException("Cannot apply directory reshape to a file entity")
          }

      is CodeVirtualWorkspace.FileReshape ->
          when (originalEntity) {
            is CodeVirtualWorkspace.Directory ->
                throw IllegalArgumentException("Cannot apply file reshape to a directory entity")
            is CodeVirtualWorkspace.File -> reshapeFile(originalFile = originalEntity)
          }
    }

fun CodeVirtualWorkspace.DirectoryReshape.reshapeDirectory(
    originalDirectory: CodeVirtualWorkspace.Directory,
): CodeVirtualWorkspace.Directory =
    when (this) {
      is CodeVirtualWorkspace.ExpandedDirectoryReshape ->
          when (originalDirectory) {
            is CodeVirtualWorkspace.ExpandedDirectory ->
                when (this) {
                  is CodeVirtualWorkspace.ExpandedDirectoryReshape.Collapse ->
                      this.collapseDirectory(
                          originalDirectory = originalDirectory,
                      )

                  is CodeVirtualWorkspace.ExpandedDirectoryReshape.ReshapeRecursively ->
                      this.reshapeRecursively(
                          originalDirectory = originalDirectory,
                      )
                }

            is CodeVirtualWorkspace.CollapsedDirectory ->
                throw IllegalArgumentException("Cannot collapse an already collapsed directory")
          }

      is CodeVirtualWorkspace.CollapsedDirectoryReshape.Expand ->
          when (originalDirectory) {
            is CodeVirtualWorkspace.ExpandedDirectory ->
                throw IllegalArgumentException("Cannot expand an already expanded directory")
            is CodeVirtualWorkspace.CollapsedDirectory ->
                this.expandDirectory(originalDirectory = originalDirectory)
          }
    }

@Suppress("UnusedReceiverParameter")
fun CodeVirtualWorkspace.ExpandedDirectoryReshape.Collapse.collapseDirectory(
    originalDirectory: CodeVirtualWorkspace.ExpandedDirectory,
): CodeVirtualWorkspace.CollapsedDirectory =
    CodeVirtualWorkspace.CollapsedDirectory(
        vcsStatus = originalDirectory.vcsStatus,
        lockState = CodeVirtualWorkspace.LockState.Locked,
    )

@Suppress("UnusedReceiverParameter")
fun CodeVirtualWorkspace.CollapsedDirectoryReshape.Expand.expandDirectory(
    originalDirectory: CodeVirtualWorkspace.CollapsedDirectory,
): CodeVirtualWorkspace.ExpandedDirectory =
    CodeVirtualWorkspace.ExpandedDirectory(
        childEntityByName = TODO(), // TODO: context
    )

fun CodeVirtualWorkspace.ExpandedDirectoryReshape.ReshapeRecursively.reshapeRecursively(
    originalDirectory: CodeVirtualWorkspace.ExpandedDirectory,
): CodeVirtualWorkspace.ExpandedDirectory =
    CodeVirtualWorkspace.ExpandedDirectory(
        childEntityByName =
            originalDirectory.childEntityByName.mapValues { (name, childEntity) ->
              when (val childReshape = childReshapeByName[name]) {
                null -> childEntity
                else -> childReshape.reshape(originalEntity = childEntity)
              }
            },
    )

fun CodeVirtualWorkspace.FileReshape.reshapeFile(
    originalFile: CodeVirtualWorkspace.File,
): CodeVirtualWorkspace.File =
    when (this) {
      is CodeVirtualWorkspace.OpenedFileReshape ->
          when (originalFile) {
            is CodeVirtualWorkspace.OpenedCodeFile ->
                when (this) {
                  is CodeVirtualWorkspace.OpenedFileReshape.Close -> this.closeFile(originalFile)
                }

            is CodeVirtualWorkspace.ClosedFile ->
                throw IllegalArgumentException("Cannot open an already opened file")
          }

      is CodeVirtualWorkspace.ClosedFileReshape.Open ->
          when (originalFile) {
            is CodeVirtualWorkspace.OpenedCodeFile ->
                throw IllegalArgumentException("Cannot close an already closed file")
            is CodeVirtualWorkspace.ClosedFile -> this.openFile(originalFile)
          }
    }

@Suppress("UnusedReceiverParameter")
fun CodeVirtualWorkspace.OpenedFileReshape.Close.closeFile(
    originalFile: CodeVirtualWorkspace.OpenedCodeFile,
): CodeVirtualWorkspace.ClosedFile =
    CodeVirtualWorkspace.ClosedFile(
        vcsStatus = originalFile.vcsStatus,
        lockState = CodeVirtualWorkspace.LockState.Locked,
    )

// FIXME
// fun CodeVirtualWorkspace.OpenedFileReshape.CollapseSection.collapseSectionInFile(
//    originalFile: CodeVirtualWorkspace.OpenedCodeFile,
// ): CodeVirtualWorkspace.OpenedCodeFile = CodeVirtualWorkspace.OpenedCodeFile(
//    structuredContent = TODO(), // TODO: context
// )
//
// fun CodeVirtualWorkspace.OpenedFileReshape.ExpandSection.expandSectionInFile(
//    originalFile: CodeVirtualWorkspace.OpenedCodeFile,
// ): CodeVirtualWorkspace.OpenedCodeFile = CodeVirtualWorkspace.OpenedCodeFile(
//    structuredContent = TODO(), // TODO: context
// )

@Suppress("UnusedReceiverParameter")
fun CodeVirtualWorkspace.ClosedFileReshape.Open.openFile(
    originalFile: CodeVirtualWorkspace.ClosedFile,
): CodeVirtualWorkspace.OpenedCodeFile {
  if (originalFile.lockState == CodeVirtualWorkspace.LockState.Locked) {
    throw IllegalStateException("Cannot open a locked file")
  }

  return CodeVirtualWorkspace.OpenedCodeFile(
      vcsStatus = originalFile.vcsStatus,
      document = TODO(), // TODO: context
  )
}

// endregion
