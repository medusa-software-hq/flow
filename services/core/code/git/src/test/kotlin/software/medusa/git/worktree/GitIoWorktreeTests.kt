package software.medusa.git.worktree

import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.isExecutable
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitIoWorktreeTests {
  @Test
  fun projectsFilesystemDirectoryAndFiles() {
    val rootPath = Files.createTempDirectory("git-io-worktree-")

    rootPath.resolve("nested").apply {
      createDirectories()
      resolve("a.txt").writeText("hello")
    }

    rootPath.resolve("b.txt").writeText("world")

    rootPath.resolve("hi.sh").apply {
      writeText("echo hi")
      toFile().setExecutable(true)
    }

    val rootDirectory = GitIoWorktreeDirectory(directoryPath = rootPath)

    assertEquals(
        expected = setOf("nested", "b.txt", "hi.sh"),
        actual = rootDirectory.entries.map { it.name }.toSet(),
    )

    val nestedDirectory = assertIs<GitIoWorktreeDirectory>(rootDirectory.read("nested"))

    val aFile = assertIs<GitIoWorktreeFile>(nestedDirectory.read("a.txt"))

    assertEquals(
        expected = "hello",
        actual = aFile.read().readAllText(),
    )

    assertFalse(aFile.isExecutable())

    val bFile = assertIs<GitIoWorktreeFile>(rootDirectory.read("b.txt"))

    assertEquals(
        expected = "world",
        actual = bFile.read().readAllText(),
    )

    assertFalse(bFile.isExecutable())

    val hiFile = assertIs<GitIoWorktreeFile>(rootDirectory.read("hi.sh"))

    assertEquals(
        expected = "echo hi",
        actual = hiFile.read().readAllText(),
    )

    assertTrue(hiFile.isExecutable())
  }

  private fun InputStream.readAllText(): String = bufferedReader().readText()
}
