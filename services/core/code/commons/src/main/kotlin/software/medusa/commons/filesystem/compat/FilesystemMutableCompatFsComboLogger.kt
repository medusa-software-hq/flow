package software.medusa.commons.filesystem.compat

import java.time.Clock
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.medusa.commons.paths.UnixPath

class FilesystemMutableCompatFsComboLogger(
    private val logDirectory: MutableCompatFsDirectory,
    private val clock: Clock,
) : MutableCompatFsComboLogger {
  companion object {
    private val subLogDirName = UnixPath.Name.Literal("sub")
    private val json = Json { prettyPrint = true }
  }

  override suspend fun getSubLogger(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsComboLogger {
    val subLoggersDirectory = logDirectory.extractOrCreateDirectory(name = subLogDirName)
    val subLoggerDirectory = subLoggersDirectory.extractOrCreateDirectory(name = name)

    return FilesystemMutableCompatFsComboLogger(
        logDirectory = subLoggerDirectory,
        clock = clock,
    )
  }

  override suspend fun logListEntries(
      listedEntries: List<ReadonlyCompatFsDirectory.Entry<MutableCompatFsEntity>>,
  ) {
    val entryDirectory = createOperationDirectory(operationName = "listEntries")

    writeTextFile(
        directory = entryDirectory,
        fileName = "listedEntries.json",
        content =
            json.encodeToString(
                ListedEntriesLog.serializer(),
                ListedEntriesLog(
                    entries =
                        listedEntries.map { listedEntry ->
                          ListedEntriesLog.Entry(
                              name = listedEntry.name.name,
                              entityType =
                                  when (listedEntry.entity) {
                                    is MutableCompatFsDirectory -> "directory"
                                    is MutableCompatFsFile -> "file"
                                  },
                          )
                        },
                ),
            ),
    )
  }

  override suspend fun logDeleteDirectory() {
    createOperationDirectory(operationName = "deleteDirectory")
  }

  override suspend fun logRead(readContent: ByteString) {
    val entryDirectory = createOperationDirectory(operationName = "read")

    writeBinaryFile(
        directory = entryDirectory,
        fileName = "readContent.bin",
        content = readContent,
    )
  }

  override suspend fun logIsExecutable(isExecutableStatus: Boolean) {
    val entryDirectory = createOperationDirectory(operationName = "isExecutable")

    writeTextFile(
        directory = entryDirectory,
        fileName = "status.json",
        content =
            json.encodeToString(
                BooleanLog.serializer(),
                BooleanLog(value = isExecutableStatus),
            ),
    )
  }

  override suspend fun logWrite(writtenContent: ByteString) {
    val entryDirectory = createOperationDirectory(operationName = "write")

    writeBinaryFile(
        directory = entryDirectory,
        fileName = "writtenContent.bin",
        content = writtenContent,
    )
  }

  override suspend fun logMakeExecutable() {
    createOperationDirectory(operationName = "makeExecutable")
  }

  override suspend fun logDeleteFile() {
    createOperationDirectory(operationName = "deleteFile")
  }

  private suspend fun createOperationDirectory(
      operationName: String,
  ): MutableCompatFsDirectory {
    val timestampedName = UnixPath.Name.Literal("${clock.millis()}-$operationName")

    return logDirectory.extractOrCreateDirectory(name = timestampedName)
  }

  private suspend fun writeTextFile(
      directory: MutableCompatFsDirectory,
      fileName: String,
      content: String,
  ) {
    directory.createFile(
        name = UnixPath.Name.Literal(fileName),
        initialContent = content.encodeToByteString(),
    )
  }

  private suspend fun writeBinaryFile(
      directory: MutableCompatFsDirectory,
      fileName: String,
      content: ByteString,
  ) {
    directory.createFile(
        name = UnixPath.Name.Literal(fileName),
        initialContent = content,
    )
  }

  @Serializable
  private data class ListedEntriesLog(
      val entries: List<Entry>,
  ) {
    @Serializable
    data class Entry(
        val name: String,
        val entityType: String,
    )
  }

  @Serializable
  private data class BooleanLog(
      val value: Boolean,
  )
}
