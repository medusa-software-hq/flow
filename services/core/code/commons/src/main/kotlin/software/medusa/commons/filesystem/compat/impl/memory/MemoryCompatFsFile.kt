package software.medusa.commons.filesystem.compat.impl.memory

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsFile

class MemoryCompatFsFile(
    initialPath: ByteString = emptyByteString,
    private var onDelete: (() -> Unit)? = null,
) : MutableCompatFsFile {
  companion object {
    val emptyByteString: ByteString = ByteString(ByteArray(0))
  }

  private var mutableContent = initialPath

  override suspend fun read(): ByteString = mutableContent

  override suspend fun write(newContent: ByteString) {
    mutableContent = newContent
  }

  override suspend fun delete() {
    val deleteSelf = onDelete ?: throw IllegalStateException("Cannot delete detached file.")

    onDelete = null
    deleteSelf()
  }
}
