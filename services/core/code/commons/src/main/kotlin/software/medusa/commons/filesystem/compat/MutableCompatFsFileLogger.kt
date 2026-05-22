package software.medusa.commons.filesystem.compat

import kotlinx.io.bytestring.ByteString

interface MutableCompatFsFileLogger {
  suspend fun logRead(
      readContent: ByteString,
  )

  suspend fun logIsExecutable(
      isExecutableStatus: Boolean,
  )

  suspend fun logWrite(
      writtenContent: ByteString,
  )

  suspend fun logMakeExecutable()

  suspend fun logDeleteFile()
}
