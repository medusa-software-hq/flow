package software.medusa.git.worktree

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.UnixPath

internal class TestGitWorktreeDirectory(
    val childByName: Map<String, ReadonlyCompatFsEntity>,
) : ReadonlyCompatFsDirectory {
  suspend fun read(name: String): ReadonlyCompatFsEntity? = extract(UnixPath.Name.Literal(name))

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): ReadonlyCompatFsEntity? = childByName[name.name]

  override suspend fun listEntries(): List<Entry<*>> = childByName.map { (name, node) ->
    Entry(
        name = UnixPath.Name.Literal(name),
        entity = node,
    )
  }
}

internal class TestGitWorktreeFile(
    private val content: ByteArray,
    private val executable: Boolean = false,
) : ReadonlyCompatFsFile {
  constructor(
      content: String,
      executable: Boolean = false,
  ) : this(
      content = content.toByteArray(Charsets.UTF_8),
      executable = executable,
  )

  override suspend fun read(): ByteString = ByteString(content)

  override suspend fun isExecutable(): Boolean = executable
}
