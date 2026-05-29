package software.medusa.commons.filesystem.compat.impl.immutable

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile

class ImmutableCompatFsFile(
    val content: ByteString,
) : ReadonlyCompatFsFile {
  constructor(
      content: String,
  ) : this(content = content.encodeToByteString())

  override suspend fun read(): ByteString = content
}
