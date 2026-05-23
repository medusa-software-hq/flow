package software.medusa.git.tree

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.ObjectReader
import org.eclipse.jgit.lib.TreeFormatter
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.git.GitCommitHash
import software.medusa.git.GitFileMode
import software.medusa.git.utils.contentEquals
import software.medusa.git.worktree.filesystem.GitRealizedWorktreeDirectory
import software.medusa.git.worktree.filesystem.GitRealizedWorktreeFile

sealed interface GitTree {
  companion object {
    fun interpret(
        worktree: ReadonlyCompatFsDirectory,
    ): GitTree = GitProperTree.interpret(worktree = worktree) ?: GitEmptyTree
  }

  fun store(
      jObjectInserter: ObjectInserter,
  ): ObjectId

  fun realize(): ReadonlyCompatFsDirectory
}

/** A special Git tree that contains no content. */
data object GitEmptyTree : GitTree {
  override fun store(
      jObjectInserter: ObjectInserter,
  ): ObjectId = jObjectInserter.insert(Constants.OBJ_TREE, ByteArray(0))

  override fun realize(): ReadonlyCompatFsDirectory = GitRealizedWorktreeDirectory(EmptyRootGroup)
}

private data object EmptyRootGroup : GitTreeGroup() {
  override val childEntries: Sequence<GitTreeGroup.ChildEntry>
    get() = emptySequence()
}

/** A proper, non-empty Git tree. */
@JvmInline
value class GitProperTree(
    val rootGroup: GitTreeGroup,
) : GitTree {
  companion object {
    fun load(
        jObjectReader: ObjectReader,
        jTreeId: ObjectId,
    ): GitProperTree =
        GitProperTree(
            rootGroup =
                GitObjectTreeGroup(
                    jObjectReader = jObjectReader,
                    jTreeId = jTreeId,
                ),
        )

    /** Interpret a Git worktree as a proper (non-empty) Git tree. */
    internal fun interpret(
        worktree: ReadonlyCompatFsDirectory,
    ): GitProperTree? {
      val rootGroup =
          GitWorktreeTreeGroup.interpret(
              worktreeDirectory = worktree,
          ) ?: return null

      return GitProperTree(
          rootGroup = rootGroup,
      )
    }
  }

  override fun store(
      jObjectInserter: ObjectInserter,
  ): ObjectId = rootGroup.storeGroup(jObjectInserter = jObjectInserter)

  override fun realize(): ReadonlyCompatFsDirectory = rootGroup.realizeGroup()
}

/** A node within a Git tree. */
sealed interface GitTreeNode

fun GitTreeNode.store(
    objectInserter: ObjectInserter,
): ObjectId =
    when (this) {
      is GitTreeGroup -> storeGroup(jObjectInserter = objectInserter)
      is GitTreeFile -> storeFile(objectInserter = objectInserter)
      is GitTreeSymlink -> storeSymlink(objectInserter = objectInserter)
      is GitTreeSubmoduleLink -> ObjectId.fromString(commitHash.raw)
    }

/**
 * A non-empty group of named child nodes within a Git tree.
 *
 * The group node is represented as "tree object" in Git.
 */
abstract class GitTreeGroup : GitTreeNode {
  data class ChildEntry(
      val name: String,
      val child: GitTreeNode,
  ) {
    internal val gitSortingName: ByteString =
        when (child) {
          is GitTreeGroup -> "$name/".encodeToByteString()
          else -> name.encodeToByteString()
        }
  }

  val childByName: Map<String, GitTreeNode>
    get() = childEntries.associate { it.name to it.child }

  abstract val childEntries: Sequence<ChildEntry>

  override fun equals(other: Any?): Boolean {
    if (other !is GitTreeGroup) return false

    return childEntries.contentEquals(other.childEntries)
  }

  override fun hashCode(): Int {
    TODO()
  }
}

private fun GitTreeGroup.storeGroup(
    jObjectInserter: ObjectInserter,
): ObjectId {
  val treeFormatter = TreeFormatter()

  val sortedChildEntries = childEntries.toList().sortedBy { it.gitSortingName }

  sortedChildEntries.forEach { childEntry ->
    val childObjectId = childEntry.child.store(jObjectInserter)

    val fileMode =
        when (val child = childEntry.child) {
          is GitTreeGroup -> FileMode.TREE
          is GitTreeFile -> child.mode.jFileMode
          is GitTreeSymlink -> FileMode.SYMLINK
          is GitTreeSubmoduleLink -> FileMode.GITLINK
        }

    treeFormatter.append(childEntry.name, fileMode, childObjectId)
  }

  return treeFormatter.insertTo(jObjectInserter)
}

fun GitTreeGroup.realizeGroup(): ReadonlyCompatFsDirectory =
    GitRealizedWorktreeDirectory(
        treeGroup = this,
    )

/**
 * A file with content within a Git tree.
 *
 * The file node is represented within Git tree as an entry with modes 100644 (normal file) or
 * 100755 (executable file), pointing to a "blob object" that contains the file content.
 */
abstract class GitTreeFile : GitTreeNode {
  abstract val mode: GitFileMode

  /**
   * Read the file content as a stream. The caller is responsible for closing the stream after use.
   */
  abstract fun read(): InputStream

  /**
   * Write the file content to the provided output stream. The caller is responsible for closing the
   * output stream after use.
   */
  open fun write(
      outputStream: OutputStream,
  ) {
    read().use { it.copyTo(outputStream) }
  }

  override fun equals(other: Any?): Boolean {
    TODO("Compare input stream + mode") // Can InputStreams be compared effectively? :)
  }

  override fun hashCode(): Int {
    TODO() // efficient InputStream hashcode?
  }
}

private fun GitTreeFile.storeFile(
    objectInserter: ObjectInserter,
): ObjectId =
    ByteArrayOutputStream().use { outputStream ->
      write(outputStream)
      objectInserter.insert(Constants.OBJ_BLOB, outputStream.toByteArray())
    }

fun GitTreeFile.realizeFile(): ReadonlyCompatFsFile =
    GitRealizedWorktreeFile(
        treeFile = this,
    )

/**
 * A symbolic link to a file/directory, typically within a Git tree.
 *
 * The symbolic link node is represented within Git tree as an entry with mode 120000, pointing to a
 * "blob object" that contains the link target path as a string.
 *
 * May refer to a non-indexed (ignored) file within the repository or non-existing file/directory (a
 * "broken" symlink). In general, it's not possible to determine whether a symlink within a Git tree
 * is broken based on the information available in the Git tree alone.
 */
data class GitTreeSymlink(
    val targetPath: UnixPath<*>,
) : GitTreeNode

private fun GitTreeSymlink.storeSymlink(
    objectInserter: ObjectInserter,
): ObjectId {
  // We might deny storing out-of-repo symlinks, absolute symlinks and we might normalize symlink
  // paths

  val targetPathText =
      when (targetPath) {
        is RelativeUnixPath -> targetPath.toUnixRelativePathString()
        is AbsoluteUnixPath -> targetPath.toUnixAbsolutePathString()
      }

  return objectInserter.insert(
      Constants.OBJ_BLOB,
      targetPathText.toByteArray(Charsets.UTF_8),
  )
}

/**
 * A link to a Git submodule repository within a Git tree.
 *
 * The submodule link node is represented within Git tree as an entry with mode 160000, pointing to
 * a "commit object" within the submodule repository that the link points to.
 *
 * The link itself doesn't contain any information about the submodule identity (e.g., its
 * repository location).
 */
@JvmInline
value class GitTreeSubmoduleLink(
    /** The commit ID within the submodule repository that the link points to. */
    val commitHash: GitCommitHash,
) : GitTreeNode

fun GitTreeSubmoduleLink.realizeSubmoduleLink(): ReadonlyCompatFsDirectory {
  throw UnsupportedOperationException("Submodule link realization is not supported yet")
}
