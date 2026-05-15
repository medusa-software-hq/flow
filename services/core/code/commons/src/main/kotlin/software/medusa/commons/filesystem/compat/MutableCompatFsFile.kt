package software.medusa.commons.filesystem.compat

import kotlinx.io.bytestring.ByteString

/**
 * A mutable file in the compatibility filesystem API.
 *
 * Implementations may write through to a real filesystem or act as an in-memory equivalent for
 * callers that need file-like behavior without depending on a concrete storage backend.
 */
interface MutableCompatFsFile : MutableCompatFsEntity, ReadonlyCompatFsFile {
  /** Replaces the full file contents with [newContent]. */
  suspend fun write(newContent: ByteString)
}
