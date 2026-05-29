package software.medusa.commons.filesystem.compat.impl.immutable

import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.paths.UnixPath

class ImmutableCompatFsDirectory(
    val childEntityByName: Map<UnixPath.Name.Literal, ReadonlyCompatFsEntity>,
) : ReadonlyCompatFsDirectory {
  override suspend fun listEntries(): List<ReadonlyCompatFsDirectory.Entry<*>> =
      childEntityByName.map { (name, entity) ->
        ReadonlyCompatFsDirectory.Entry(
            name = name,
            entity = entity,
        )
      }

  override suspend fun extract(name: UnixPath.Name.Literal): ReadonlyCompatFsEntity? =
      childEntityByName[name]
}
