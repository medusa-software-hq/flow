package software.medusa.commons.filesystem.compat

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath

/**
 * A read-only view of a directory in the compatibility filesystem API.
 *
 * This abstraction is intended both for directories backed by a real filesystem and for generated
 * directory views assembled in memory or derived from some other source.
 */
interface ReadonlyCompatFsDirectory {
  /** A named direct child of a directory. */
  data class Entry<out EntityT : ReadonlyCompatFsEntity>(
      val name: UnixPath.Name.Literal,
      val entity: EntityT,
  )

  /** Returns the direct children of this directory. */
  suspend fun listEntries(): List<Entry<*>>

  /** Returns the direct child named [name], or `null` when no such child exists. */
  suspend fun extract(
      name: UnixPath.Name.Literal,
  ): ReadonlyCompatFsEntity?
}

/**
 * Traverses [relativePath] starting from this entity.
 *
 * Returns `null` when any path component does not exist or when traversal would need to descend
 * through a file.
 */
suspend fun ReadonlyCompatFsEntity.extract(
    relativePath: LiteralRelativeUnixPath,
): ReadonlyCompatFsEntity? {
  var currentEntity: ReadonlyCompatFsEntity = this

  for (name in relativePath.names) {
    if (currentEntity !is ReadonlyCompatFsDirectory) {
      return null
    }

    val nextEntity = currentEntity.extract(name) ?: return null

    currentEntity = nextEntity
  }

  return currentEntity
}

/**
 * Copies all direct and nested entries from this directory into [targetDirectory].
 *
 * Existing entries at matching names are overwritten. Entries present only in [targetDirectory] are
 * left untouched.
 */
suspend fun ReadonlyCompatFsDirectory.copyRecursivelyTo(
    targetDirectory: MutableCompatFsDirectory,
): Unit = copyRecursivelyToImpl(targetDirectory)

private suspend fun ReadonlyCompatFsDirectory.copyRecursivelyToImpl(
    targetDirectory: MutableCompatFsDirectory,
) {
  for (entry in listEntries()) {
    val sourceEntity = entry.entity
    val existingTargetEntity = targetDirectory.extract(entry.name)

    when (sourceEntity) {
      is ReadonlyCompatFsFile -> {
        val sourceContent = sourceEntity.read()

        when (existingTargetEntity) {
          null ->
              targetDirectory.createFile(
                  name = entry.name,
                  initialContent = sourceContent,
              )

          is MutableCompatFsFile -> existingTargetEntity.write(sourceContent)

          is MutableCompatFsDirectory -> {
            existingTargetEntity.deleteRecursively()

            targetDirectory.createFile(
                name = entry.name,
                initialContent = sourceContent,
            )
          }
        }
      }

      is ReadonlyCompatFsDirectory -> {
        val targetSubdirectory =
            when (existingTargetEntity) {
              null -> targetDirectory.createDirectory(entry.name)

              is MutableCompatFsDirectory -> existingTargetEntity

              is MutableCompatFsFile -> {
                existingTargetEntity.delete()

                targetDirectory.createDirectory(entry.name)
              }
            }

        sourceEntity.copyRecursivelyToImpl(targetSubdirectory)
      }
    }
  }
}
