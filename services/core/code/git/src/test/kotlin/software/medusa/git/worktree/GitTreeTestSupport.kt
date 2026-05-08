package software.medusa.git.worktree

import java.io.InputStream
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.git.GitFileMode
import software.medusa.git.tree.GitTreeFile
import software.medusa.git.tree.GitTreeGroup

internal class TestGitTreeGroup(
    override val childEntries: Sequence<ChildEntry>,
) : GitTreeGroup()

internal class TestGitTreeFile(
    val content: ByteString,
    override val mode: GitFileMode = GitFileMode.Regular,
) : GitTreeFile() {
  constructor(
      content: String,
      mode: GitFileMode = GitFileMode.Regular,
  ) : this(
      content = content.encodeToByteString(),
      mode = mode,
  )

  override fun read(): InputStream = content.toByteArray().inputStream()
}
