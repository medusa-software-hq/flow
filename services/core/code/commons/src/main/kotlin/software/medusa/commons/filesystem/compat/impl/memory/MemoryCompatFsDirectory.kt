package software.medusa.commons.filesystem.compat.impl.memory

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsEntity
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.paths.UnixPath

class MemoryCompatFsDirectory(
    private val onDelete: (() -> Unit) = {},
) : MutableCompatFsDirectory {
  private val entityByName = mutableMapOf<UnixPath.Name.Literal, MutableCompatFsEntity>()

  private var wasDeleted = false

  override suspend fun listEntries(): List<Entry<MutableCompatFsEntity>> =
      entityByName.map { (name, entity) ->
        Entry(
            name = name,
            entity = entity,
        )
      }

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsEntity? = entityByName[name]

  override suspend fun createFile(
      name: UnixPath.Name.Literal,
      initialContent: ByteString?,
  ): MutableCompatFsFile {
    if (entityByName.contains(name)) {
      throw IllegalStateException(
          "Cannot create file with name ${name.name}, because an entity with the same name already exists.",
      )
    }

    val newFile =
        MemoryCompatFsFile(
            initialPath = initialContent ?: MemoryCompatFsFile.emptyByteString,
            onDelete = { entityByName.remove(name) },
        )

    entityByName[name] = newFile

    return newFile
  }

  override suspend fun createDirectory(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsDirectory {
    if (entityByName.contains(name)) {
      throw IllegalStateException(
          "Cannot create directory with name ${name.name}, because an entity with the same name already exists.",
      )
    }

    val newDirectory =
        MemoryCompatFsDirectory(
            onDelete = { entityByName.remove(name) },
        )

    entityByName[name] = newDirectory

    return newDirectory
  }

  override suspend fun delete() {
    if (wasDeleted) {
      throw IllegalStateException("Directory was already deleted.")
    }

    if (entityByName.isNotEmpty()) {
      throw IllegalStateException(
          "Cannot delete directory, because it is not empty.",
      )
    }

    onDelete()

    wasDeleted = true
  }
}
