package software.medusa.commons.filesystem.compat

import software.medusa.commons.paths.UnixPath

interface MutableCompatFsDirectoryLogger {
  suspend fun getSubLogger(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsComboLogger

  suspend fun logListEntries(
      listedEntries: List<ReadonlyCompatFsDirectory.Entry<MutableCompatFsEntity>>,
  )

  suspend fun logDeleteDirectory()
}
