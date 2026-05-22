package software.medusa.commons.filesystem.compat

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.paths.UnixPath

class LoggingMutableCompatFsDirectory(
    private val baseDirectory: MutableCompatFsDirectory,
    private val logger: MutableCompatFsDirectoryLogger,
) : MutableCompatFsDirectory {
  override suspend fun listEntries(): List<ReadonlyCompatFsDirectory.Entry<MutableCompatFsEntity>> {
    val baseEntries = baseDirectory.listEntries()
    logger.logListEntries(listedEntries = baseEntries)

    return baseEntries.map { baseEntry ->
      val subLogger = logger.getSubLogger(name = baseEntry.name)

      val wrappedEntity =
          when (val baseEntity = baseEntry.entity) {
            is MutableCompatFsDirectory ->
                LoggingMutableCompatFsDirectory(
                    baseDirectory = baseEntity,
                    logger = subLogger,
                )

            is MutableCompatFsFile ->
                LoggingMutableCompatFsFile(
                    baseFile = baseEntity,
                    logger = subLogger,
                )
          }

      baseEntry.copy(
          entity = wrappedEntity,
      )
    }
  }

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsEntity? {
    val baseEntity = baseDirectory.extract(name) ?: return null
    val subLogger = logger.getSubLogger(name = name)

    return when (baseEntity) {
      is MutableCompatFsDirectory ->
          LoggingMutableCompatFsDirectory(
              baseDirectory = baseEntity,
              logger = subLogger,
          )

      is MutableCompatFsFile ->
          LoggingMutableCompatFsFile(
              baseFile = baseEntity,
              logger = subLogger,
          )
    }
  }

  /**
   * Creates a new file named [name].
   *
   * Assumes that no file or directory with the same name already exists.
   */
  override suspend fun createFile(
      name: UnixPath.Name.Literal,
      initialContent: ByteString?,
  ): MutableCompatFsFile {
    val baseFile = baseDirectory.createFile(name = name, initialContent = initialContent)
    val subLogger = logger.getSubLogger(name = name)

    initialContent?.let { content -> subLogger.logWrite(writtenContent = content) }

    return LoggingMutableCompatFsFile(
        baseFile = baseFile,
        logger = subLogger,
    )
  }

  /**
   * Creates a new directory named [name].
   *
   * Assumes that no file or directory with the same name already exists.
   */
  override suspend fun createDirectory(name: UnixPath.Name.Literal): MutableCompatFsDirectory {
    val baseSubdirectory = baseDirectory.createDirectory(name = name)
    val subLogger = logger.getSubLogger(name = name)

    return LoggingMutableCompatFsDirectory(
        baseDirectory = baseSubdirectory,
        logger = subLogger,
    )
  }

  /** Deletes this entity. Directories must be empty before deletion. */
  override suspend fun delete() {
    baseDirectory.delete()
    logger.logDeleteDirectory()
  }
}

private class LoggingMutableCompatFsFile(
    private val baseFile: MutableCompatFsFile,
    private val logger: MutableCompatFsFileLogger,
) : MutableCompatFsFile {
  override suspend fun read(): ByteString {
    val content = baseFile.read()
    logger.logRead(readContent = content)
    return content
  }

  override suspend fun write(newContent: ByteString) {
    baseFile.write(newContent = newContent)
    logger.logWrite(writtenContent = newContent)
  }

  override suspend fun makeExecutable() {
    baseFile.makeExecutable()
    logger.logMakeExecutable()
  }

  override suspend fun isExecutable(): Boolean {
    val isExecutable = baseFile.isExecutable()
    logger.logIsExecutable(isExecutableStatus = isExecutable)
    return isExecutable
  }

  override suspend fun delete() {
    baseFile.delete()
    logger.logDeleteFile()
  }
}
