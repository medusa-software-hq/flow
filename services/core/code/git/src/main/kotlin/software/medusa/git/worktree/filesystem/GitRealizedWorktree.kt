package software.medusa.git.worktree.filesystem

import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory.Entry
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsEntity
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.UnixPath
import software.medusa.git.GitFileMode
import software.medusa.git.tree.GitTreeFile
import software.medusa.git.tree.GitTreeGroup
import software.medusa.git.tree.GitTreeNode
import software.medusa.git.tree.GitTreeSubmoduleLink
import software.medusa.git.tree.GitTreeSymlink

class GitRealizedWorktreeDirectory(
    private val treeGroup: GitTreeGroup,
) : ReadonlyCompatFsDirectory {
  override suspend fun listEntries(): List<Entry<*>> =
      treeGroup.childEntries
          .mapNotNull { childEntry ->
            val compatEntity = childEntry.child.realizeEntity() ?: return@mapNotNull null

            Entry(
                name = UnixPath.Name.Literal(childEntry.name),
                entity = compatEntity,
            )
          }
          .toList()

  override suspend fun extract(
      name: UnixPath.Name.Literal,
  ): ReadonlyCompatFsEntity? = treeGroup.childByName[name.name]?.realizeEntity()
}

class GitRealizedWorktreeFile(
    private val treeFile: GitTreeFile,
) : ReadonlyCompatFsFile {
  override suspend fun read(): ByteString = ByteString(treeFile.read().readBytes())

  override suspend fun isExecutable(): Boolean = treeFile.mode == GitFileMode.Executable
}

private fun GitTreeNode.realizeEntity(): ReadonlyCompatFsEntity? =
    when (this) {
      is GitTreeGroup -> GitRealizedWorktreeDirectory(treeGroup = this)
      is GitTreeFile -> GitRealizedWorktreeFile(treeFile = this)
      is GitTreeSymlink ->
          throw UnsupportedOperationException("Symlink filesystem views are not supported yet")
      is GitTreeSubmoduleLink ->
          throw UnsupportedOperationException("Submodule filesystem views are not supported yet")
    }
