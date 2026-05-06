package software.medusa.git.worktree

import java.io.InputStream
import software.medusa.git.GitFileMode
import software.medusa.git.tree.GitTreeFile
import software.medusa.git.tree.GitTreeGroup
import software.medusa.git.tree.realizeNode

class GitRealizedWorktreeDirectory(
    private val treeGroup: GitTreeGroup,
) : GitWorktreeDirectory() {
  private val childByName: Map<String, GitWorktreeNode> by lazy {
    treeGroup.childEntries.associate { it.name to it.child.realizeNode() }
  }

  override fun read(
      name: String,
  ): GitWorktreeNode? = childByName[name]

  override val entries: Sequence<Entry> =
      treeGroup.childEntries.map {
        Entry(
            name = it.name,
            node = it.child.realizeNode(),
        )
      }
}

class GitRealizedWorktreeFile(
    private val treeFile: GitTreeFile,
) : GitWorktreeFile() {
  override fun read(): InputStream = treeFile.read()

  override fun isExecutable(): Boolean = treeFile.mode == GitFileMode.Executable
}
