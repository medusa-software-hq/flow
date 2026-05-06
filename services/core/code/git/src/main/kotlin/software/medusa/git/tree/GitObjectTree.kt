package software.medusa.git.tree

import java.io.InputStream
import java.io.OutputStream
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.treewalk.TreeWalk
import software.medusa.git.GitFileMode
import software.medusa.git.UnixPath

/** A tree group node backed by a Git tree object. */
internal class GitObjectTreeGroup(
    private val jObjectReader: ObjectReader,
    private val jTreeId: ObjectId,
) : GitTreeGroup() {
  override val childEntries: Sequence<ChildEntry> = sequence {
    TreeWalk(jObjectReader).use { jTreeWalk ->
      jTreeWalk.addTree(jTreeId)

      while (jTreeWalk.next()) {
        val childName = jTreeWalk.nameString

        val jChildObjectId = jTreeWalk.getObjectId(0)
        val jFileMode = jTreeWalk.getFileMode(0)

        val childNode =
            GitObjectTreeUtils.fromObject(
                jObjectReader = jObjectReader,
                jChildObjectId = jChildObjectId,
                jFileMode = jFileMode,
            )

        yield(
            ChildEntry(
                name = childName,
                child = childNode,
            ),
        )
      }
    }
  }
}

/** A tree file node backed by a Git object. */
internal class GitObjectFile(
    private val jObjectReader: ObjectReader,
    private val jObjectId: ObjectId,
    override val mode: GitFileMode,
) : GitTreeFile() {
  override fun read(): InputStream {
    val objectLoader = jObjectReader.open(jObjectId)
    return objectLoader.openStream()
  }

  override fun write(
      outputStream: OutputStream,
  ) {
    val objectLoader = jObjectReader.open(jObjectId)
    objectLoader.copyTo(outputStream)
  }
}

internal object GitObjectTreeUtils {
  fun fromObject(
      jObjectReader: ObjectReader,
      jChildObjectId: ObjectId,
      jFileMode: FileMode,
  ): GitTreeNode =
      when (jFileMode) {
        FileMode.REGULAR_FILE,
        FileMode.EXECUTABLE_FILE -> {

          val fileMode =
              when (jFileMode) {
                FileMode.REGULAR_FILE -> GitFileMode.Regular
                FileMode.EXECUTABLE_FILE -> GitFileMode.Executable
                else -> throw IllegalStateException("Unexpected file mode: $jFileMode")
              }

          GitObjectFile(
              jObjectReader = jObjectReader,
              jObjectId = jChildObjectId,
              mode = fileMode,
          )
        }

        FileMode.TREE -> {
          GitObjectTreeGroup(
              jObjectReader = jObjectReader,
              jTreeId = jChildObjectId,
          )
        }

        FileMode.SYMLINK -> {
          val targetPathText =
              jObjectReader.open(jChildObjectId).cachedBytes.toString(Charsets.UTF_8)

          val targetPath = UnixPath.parse(targetPathText)

          GitTreeSymlink(targetPath = targetPath)
        }

        else ->
            throw IllegalStateException(
                "Unsupported file mode: $jFileMode",
            )
      }
}
