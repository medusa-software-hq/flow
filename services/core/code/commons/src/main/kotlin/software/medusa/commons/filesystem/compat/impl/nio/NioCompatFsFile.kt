package software.medusa.commons.filesystem.compat.impl.nio

import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsFile

class NioCompatFsFile(
    private val filePath: Path,
) : MutableCompatFsFile {
  override suspend fun read(): ByteString =
      withContext(Dispatchers.IO) { ByteString(filePath.readBytes()) }

  override suspend fun write(newContent: ByteString) =
      withContext(Dispatchers.IO) { filePath.writeBytes(newContent.toByteArray()) }

  override suspend fun delete() = withContext(Dispatchers.IO) { filePath.deleteExisting() }
}
