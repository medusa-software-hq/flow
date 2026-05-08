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
import software.medusa.git.GitCommitDetails
import software.medusa.git.GitFileMode
import software.medusa.git.GitPersonalDetails
import software.medusa.git.GitRepository
import software.medusa.git.GitRepository.Companion.readCommit
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

      val commitHash =
          repository.createCommitWithTree(
              treeId = dumpedTreeId,
              message = "seed object tree",
          )

      val objectTree =
          GitRepository.open(repoPath).process { readCommit(commitHash).tree.rootGroup }

      val helloFile = assertIs<GitObjectTreeFile>(objectTree.childByName.getValue("hello.txt"))
      assertEquals(GitFileMode.Regular, helloFile.mode)
      assertEquals("hello", helloFile.read().bufferedReader().readText())

      val toolFile = assertIs<GitObjectTreeFile>(objectTree.childByName.getValue("tool.sh"))
      assertEquals(GitFileMode.Executable, toolFile.mode)
      assertEquals("echo hi", toolFile.read().bufferedReader().readText())

      val nestedGroup = assertIs<GitObjectTreeGroup>(objectTree.childByName.getValue("nested"))
      val nestedFile = assertIs<GitObjectTreeFile>(nestedGroup.childByName.getValue("world.txt"))
      assertEquals("world", nestedFile.read().bufferedReader().readText())
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

        val treeId =
            TreeFormatter()
                .apply { append("symlink", FileMode.SYMLINK, symlinkObjectId) }
                .insertTo(objectInserter)

        val commitHash =
            repository.createCommitWithTree(
                treeId = treeId,
                message = "seed symlink tree",
            )

        val symlinkNode =
            GitRepository.open(repoPath).process {
              readCommit(commitHash).tree.rootGroup.childByName.getValue("symlink")
            }

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

private fun org.eclipse.jgit.lib.Repository.createCommitWithTree(
    treeId: ObjectId,
    message: String,
): software.medusa.git.GitCommitHash {
  val details =
      GitCommitDetails(
          authorDetails = GitPersonalDetails(name = "Test Author", email = "author@example.com"),
          committerDetails =
              GitPersonalDetails(name = "Test Committer", email = "committer@example.com"),
          message = message,
      )

  val commitId =
      newObjectInserter().use { objectInserter ->
        objectInserter.insert(
            org.eclipse.jgit.lib.CommitBuilder().apply {
              author = details.authorDetails.personIdent
              committer = details.committerDetails?.personIdent ?: details.authorDetails.personIdent
              this.message = details.message
              setTreeId(treeId)
            },
        )
      }

  val headTargetRefName = exactRef(Constants.HEAD).target.name
  val refUpdate = updateRef(headTargetRefName)
  refUpdate.setNewObjectId(commitId)

  when (val updateResult = refUpdate.update()) {
    org.eclipse.jgit.lib.RefUpdate.Result.NEW,
    org.eclipse.jgit.lib.RefUpdate.Result.FAST_FORWARD,
    org.eclipse.jgit.lib.RefUpdate.Result.NO_CHANGE,
    -> return software.medusa.git.GitCommitHash(commitId.name)

    else -> error("Failed to create commit for tree $treeId: $updateResult")
  }
}
