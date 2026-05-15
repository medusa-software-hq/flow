package software.medusa.commons.filesystem.compat

import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString

/**
 * A read-only view of a file in the compatibility filesystem API.
 *
 * Like the rest of the read-only surface, this may represent either a real filesystem entry behind
 * a facade or a file exposed from a generated filesystem-shaped view.
 */
interface ReadonlyCompatFsFile {
  /** Reads the full file contents. */
  suspend fun read(): ByteString
}

/** Reads the full file contents and decodes them as UTF-8 text. */
suspend fun ReadonlyCompatFsFile.readText(): String = read().decodeToString()
