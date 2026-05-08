package software.medusa.git

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.encodeToByteString
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.ObjectChecker
import org.eclipse.jgit.treewalk.TreeWalk
import software.medusa.git.tree.GitTreeFile
import software.medusa.git.tree.GitTreeGroup
import software.medusa.git.tree.store

class GitTreeStoreTests {
  @Test
  fun storedTreeCanBeReadBackFromJgit() {
    val helloFileName = "hello.txt"
    val helloFileContent = "hello"

    val nestedDirName = "nested"

    val worldFileName = "world.txt"
    val worldFileContent = "world!"

    val binFileName = "file.bin"
    val binFileContent =
        ByteString(
            0xDE.toByte(),
            0xAD.toByte(),
            0xBE.toByte(),
            0xEF.toByte(),
        )

    val shFileName = "hi.sh"
    val shFileContent =
        """
        #!/bin/bash
        echo hi
        """
            .trimIndent()

    val testTreeGroup =
        TestTreeGroup(
            childEntries =
                sequenceOf(
                    GitTreeGroup.ChildEntry(
                        name = helloFileName,
                        child =
                            TestTreeFile(
                                content = helloFileContent.encodeToByteString(),
                            ),
                    ),
                    GitTreeGroup.ChildEntry(
                        name = nestedDirName,
                        child =
                            TestTreeGroup(
                                childEntries =
                                    sequenceOf(
                                        GitTreeGroup.ChildEntry(
                                            name = worldFileName,
                                            child =
                                                TestTreeFile(
                                                    content = worldFileContent.encodeToByteString(),
                                                ),
                                        ),
                                    ),
                            ),
                    ),
                    GitTreeGroup.ChildEntry(
                        name = binFileName,
                        child =
                            TestTreeFile(
                                content = binFileContent,
                            ),
                    ),
                    GitTreeGroup.ChildEntry(
                        name = shFileName,
                        child =
                            TestTreeFile(
                                content = shFileContent.encodeToByteString(),
                                mode = GitFileMode.Executable,
                            ),
                    ),
                ),
        )

    val repoPath = Files.createTempDirectory("git-tree-dump-")

    val git = Git.init().setDirectory(repoPath.toFile()).call()
    val gitRepository = git.repository

    val treeEntries = gitRepository.use { repository ->
      val treeId =
          repository.newObjectInserter().use { objectInserter ->
            testTreeGroup.store(objectInserter)
          }

      ObjectChecker().checkTree(repository.open(treeId).cachedBytes)

      repository.walk(treeId).toSet()
    }

    assertEquals(
        expected =
            setOf(
                TreeWalkEntry(
                    path = helloFileName,
                    mode = FileMode.REGULAR_FILE,
                    content = helloFileContent.encodeToByteString(),
                ),
                TreeWalkEntry(
                    path = "$nestedDirName/$worldFileName",
                    mode = FileMode.REGULAR_FILE,
                    content = worldFileContent.encodeToByteString(),
                ),
                TreeWalkEntry(
                    path = binFileName,
                    mode = FileMode.REGULAR_FILE,
                    content = binFileContent,
                ),
                TreeWalkEntry(
                    path = shFileName,
                    mode = FileMode.EXECUTABLE_FILE,
                    content = shFileContent.encodeToByteString(),
                ),
            ),
        actual = treeEntries,
    )
  }
}

private data class TreeWalkEntry(
    val path: String,
    val mode: FileMode,
    val content: ByteString,
)

private fun Repository.walk(treeId: ObjectId): Sequence<TreeWalkEntry> {
  val repository = this

  return sequence {
    TreeWalk(repository).use { treeWalk ->
      treeWalk.addTree(treeId)
      treeWalk.isRecursive = true

      while (treeWalk.next()) {
        val path = treeWalk.pathString
        val objectId = treeWalk.getObjectId(0)
        val fileMode = treeWalk.getFileMode(0)

        val objectLoader = repository.open(objectId)

        yield(
            TreeWalkEntry(
                path = path,
                mode = fileMode,
                content = ByteString(objectLoader.cachedBytes),
            ),
        )
      }
    }
  }
}

private class TestTreeGroup(
    override val childEntries: Sequence<ChildEntry>,
) : GitTreeGroup()

private class TestTreeFile(
    private val content: ByteString,
    override val mode: GitFileMode = GitFileMode.Regular,
) : GitTreeFile() {
  override fun read() = ByteArrayInputStream(content.toByteArray())
}
