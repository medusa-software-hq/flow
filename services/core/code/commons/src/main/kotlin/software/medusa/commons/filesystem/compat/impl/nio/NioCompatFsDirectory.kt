package software.medusa.commons.filesystem.compat.impl.nio

import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsEntity
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.paths.UnixPath

class NioCompatFsDirectory(
    private val directoryPath: Path,
) : MutableCompatFsDirectory {
  override suspend fun listEntries(): List<Entry<MutableCompatFsEntity>> =
      withContext(Dispatchers.IO) {
        directoryPath.listDirectoryEntries().map { entityPath ->
          val name =
              UnixPath.Name.Literal(
                  name = entityPath.name,
              )

          val compatEntity =
              NioCompatFsEntity_utils.load(
                  entityPath = entityPath,
              )

          Entry(
              name = name,
              entity = compatEntity,
          )
        }
      }

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsEntity? =
      withContext(Dispatchers.IO) {
        val entityPath = directoryPath.resolve(name.name)

        when {
          entityPath.exists() ->
              NioCompatFsEntity_utils.load(
                  entityPath = entityPath,
              )

          else -> null
        }
      }

  override suspend fun createFile(
      name: UnixPath.Name.Literal,
      initialContent: ByteString?,
  ): MutableCompatFsFile =
      withContext(Dispatchers.IO) {
        val newFilePath = directoryPath.resolve(name.name)

        newFilePath.createFile()

        if (initialContent != null) {
          newFilePath.writeBytes(initialContent.toByteArray())
        }

        NioCompatFsFile(
            filePath = newFilePath,
        )
      }

  override suspend fun createDirectory(
      name: UnixPath.Name.Literal,
  ): MutableCompatFsDirectory =
      withContext(Dispatchers.IO) {
        val newDirectoryPath = directoryPath.resolve(name.name)

        newDirectoryPath.createDirectory()

        NioCompatFsDirectory(
            directoryPath = newDirectoryPath,
        )
      }

  override suspend fun delete() = withContext(Dispatchers.IO) { directoryPath.deleteExisting() }
}
