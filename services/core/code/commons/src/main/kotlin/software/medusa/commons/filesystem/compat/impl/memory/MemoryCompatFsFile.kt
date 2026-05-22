package software.medusa.commons.filesystem.compat.impl.memory

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsFile

class MemoryCompatFsFile(
    initialPath: ByteString = emptyByteString,
    private val onDelete: (() -> Unit) = {},
) : MutableCompatFsFile {
  companion object {
    val emptyByteString: ByteString = ByteString(ByteArray(0))
  }

  private var mutableContent = initialPath
  private var isExecutable = false
  private var wasDeleted = false

  override suspend fun read(): ByteString = mutableContent

  override suspend fun write(newContent: ByteString) {
    mutableContent = newContent
  }

  override suspend fun makeExecutable() {
    isExecutable = true
  }

  override suspend fun isExecutable(): Boolean = isExecutable

  override suspend fun delete() {
    if (wasDeleted) {
      throw IllegalStateException("Cannot delete file, because it has already been deleted.")
    }

    onDelete()

    wasDeleted = true
  }
}
