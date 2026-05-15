package software.medusa.commons.filesystem.compat

import software.medusa.commons.paths.LiteralRelativeUnixPath

/**
 * A mutable filesystem entity exposed through the compatibility API.
 *
 * Implementations are expected to be backed either by a real writable filesystem or by a mutable
 * in-memory stand-in that behaves similarly enough for callers that do not care about the storage
 * medium.
 */
sealed interface MutableCompatFsEntity : ReadonlyCompatFsEntity {
  /** Deletes this entity. Directories must be empty before deletion. */
  suspend fun delete()
}

/** Deletes this entity and, if it is a directory, all nested children recursively. */
suspend fun MutableCompatFsEntity.deleteRecursively() {
  when (this) {
    is MutableCompatFsFile -> delete()

    is MutableCompatFsDirectory -> {
      for (entry in listEntries()) {
        entry.entity.deleteRecursively()
      }

      delete()
    }
  }
}

/**
 * Traverses [relativePath] starting from this entity.
 *
 * Returns `null` when any path component does not exist or when traversal would need to descend
 * through a file.
 */
suspend fun MutableCompatFsEntity.extractDeepMutable(
    relativePath: LiteralRelativeUnixPath,
): MutableCompatFsEntity? {
  var currentEntity: MutableCompatFsEntity = this

  for (name in relativePath.names) {
    if (currentEntity !is MutableCompatFsDirectory) {
      return null
    }

    val nextEntity = currentEntity.extract(name) ?: return null

    currentEntity = nextEntity
  }

  return currentEntity
}
