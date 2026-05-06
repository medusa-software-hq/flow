package software.medusa.git.tree

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.TreeFormatter
import software.medusa.git.GitFileMode
import software.medusa.git.UnixPath

class GitObjectTreeTests {
  @Test
  fun readsDumpedJgitTreeBackIntoGitObjectTreeAbstraction() {
    val repoPath = Files.createTempDirectory("git-object-tree-")

    val git = Git.init().setDirectory(repoPath.toFile()).call()
    val gitRepository = git.repository

    gitRepository.use { repository ->
      val dumpedTreeId =
          repository.newObjectInserter().use { objectInserter ->
            val helloBlobId = objectInserter.insertBlob("hello")
            val toolBlobId = objectInserter.insertBlob("echo hi")
            val worldBlobId = objectInserter.insertBlob("world")

            val nestedTreeId =
                TreeFormatter()
                    .apply { append("world.txt", FileMode.REGULAR_FILE, worldBlobId) }
                    .insertTo(objectInserter)

            TreeFormatter()
                .apply {
                  append("hello.txt", FileMode.REGULAR_FILE, helloBlobId)
                  append("tool.sh", FileMode.EXECUTABLE_FILE, toolBlobId)
                  append("nested", FileMode.TREE, nestedTreeId)
                }
                .insertTo(objectInserter)
          }

      repository.newObjectReader().use { objectReader ->
        val objectTree =
            GitObjectTreeGroup(
                jObjectReader = objectReader,
                jTreeId = dumpedTreeId,
            )

        val helloFile = assertIs<GitObjectFile>(objectTree.childByName.getValue("hello.txt"))
        assertEquals(GitFileMode.Regular, helloFile.mode)
        assertEquals("hello", helloFile.read().bufferedReader().readText())

        val toolFile = assertIs<GitObjectFile>(objectTree.childByName.getValue("tool.sh"))
        assertEquals(GitFileMode.Executable, toolFile.mode)
        assertEquals("echo hi", toolFile.read().bufferedReader().readText())

        val nestedGroup = assertIs<GitObjectTreeGroup>(objectTree.childByName.getValue("nested"))
        val nestedFile = assertIs<GitObjectFile>(nestedGroup.childByName.getValue("world.txt"))
        assertEquals("world", nestedFile.read().bufferedReader().readText())
      }
    }
  }

  @Test
  fun readsAbsoluteSymlinkObjects() {
    val repoPath = Files.createTempDirectory("git-object-tree-symlink-")

    Git.init().setDirectory(repoPath.toFile()).call().repository.use { repository ->
      repository.newObjectInserter().use { objectInserter ->
        val symlinkObjectId =
            objectInserter.insert(
                Constants.OBJ_BLOB,
                "/tmp/target".toByteArray(),
            )

        val symlinkNode =
            GitObjectTreeUtils.fromObject(
                jObjectReader = repository.newObjectReader(),
                jChildObjectId = symlinkObjectId,
                jFileMode = FileMode.SYMLINK,
            )

        val absoluteSymlink = assertIs<GitTreeSymlink>(symlinkNode)

        assertEquals(
            expected = UnixPath.Absolute.of("tmp", "target"),
            actual = absoluteSymlink.targetPath,
        )
      }
    }
  }
}

private fun ObjectInserter.insertBlob(content: String): ObjectId =
    insert(Constants.OBJ_BLOB, content.toByteArray())
