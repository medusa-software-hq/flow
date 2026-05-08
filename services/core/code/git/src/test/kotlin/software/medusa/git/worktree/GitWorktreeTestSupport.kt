package software.medusa.git.worktree

import java.io.ByteArrayInputStream
import java.io.InputStream

internal class TestGitWorktreeDirectory(
    private val childByName: Map<String, GitWorktreeNode>,
) : GitWorktreeDirectory() {
  override fun read(name: String): GitWorktreeNode? = childByName[name]

  override val entries: Sequence<GitWorktreeDirectory.Entry>
    get() = childByName.asSequence().map { (name, node) -> Entry(name = name, node = node) }
}

internal class TestGitWorktreeFile(
    private val content: ByteArray,
    private val executable: Boolean = false,
) : GitWorktreeFile() {
  constructor(
      content: String,
      executable: Boolean = false,
  ) : this(
      content = content.toByteArray(Charsets.UTF_8),
      executable = executable,
  )

  override fun read(): InputStream = ByteArrayInputStream(content)

  override fun isExecutable(): Boolean = executable
}
