package software.medusa.commons.filesystem.compat

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
