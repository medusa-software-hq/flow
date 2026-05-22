package software.medusa.commons.filesystem.compat

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.paths.UnixPath

/**
 * A mutable directory in the compatibility filesystem API.
 *
 * This is designed to work equally well for adapters over a real writable filesystem and for
 * in-memory directory implementations used in tests, staging, or generated workspaces.
 */
interface MutableCompatFsDirectory : MutableCompatFsEntity, ReadonlyCompatFsDirectory {
  /** Returns the direct mutable children of this directory. */
  override suspend fun listEntries(): List<Entry<MutableCompatFsEntity>>

  /** Returns the mutable direct child named [name], or `null` when no such child exists. */
  override suspend fun extract(name: UnixPath.Name.Literal): MutableCompatFsEntity?

  /**
   * Creates a new file named [name].
   *
   * Assumes that no file or directory with the same name already exists.
   */
  suspend fun createFile(
      name: UnixPath.Name.Literal,
      initialContent: ByteString?,
  ): MutableCompatFsFile

  /**
   * Creates a new directory named [name].
   *
   * Assumes that no file or directory with the same name already exists.
   */
  suspend fun createDirectory(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsDirectory
}

/**
 * Returns the mutable direct child named [name], creating a new directory if no such child exists.
 *
 * @throws IllegalStateException if a file with the same name already exists.
 */
suspend fun MutableCompatFsDirectory.extractOrCreateDirectory(
    name: UnixPath.Name.Literal,
): MutableCompatFsDirectory =
    when (val existingEntity = extract(name = name)) {
      is MutableCompatFsDirectory -> existingEntity

      is MutableCompatFsFile -> {
        throw IllegalStateException("Expected a directory named `${name.name}`, but found a file")
      }

      null -> createDirectory(name = name)
    }
