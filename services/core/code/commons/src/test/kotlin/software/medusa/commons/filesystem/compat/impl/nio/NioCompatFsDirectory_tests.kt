package software.medusa.commons.filesystem.compat.impl.nio

import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsEntity_testUtils.withTempDir
import software.medusa.commons.paths.UnixPath

class NioCompatFsDirectory_tests {
  @Test
  fun test_listEntries_empty() = runTest {
    withTempDir { tempDirPath ->
      val compatDirectory = NioCompatFsDirectory(tempDirPath)

      assertEquals(
          expected = emptyList(),
          actual = compatDirectory.listEntries(),
      )
    }
  }

  @Test
  fun test_listEntries_non_empty() = runTest {
    withTempDir { tempDirPath ->
      tempDirPath.resolve("a.txt").createFile()
      tempDirPath.resolve("a.txt").writeBytes("a".encodeToByteString().toByteArray())
      tempDirPath.resolve("nested").createDirectory()

      val compatDirectory = NioCompatFsDirectory(tempDirPath)
      val entries = compatDirectory.listEntries().sortedBy { it.name.name }

      assertEquals(
          expected = listOf("a.txt", "nested"),
          actual = entries.map { it.name.name },
      )

      assertIs<NioCompatFsFile>(entries[0].entity)
      assertIs<NioCompatFsDirectory>(entries[1].entity)
    }
  }

  @Test
  fun test_extract_nonExisting() = runTest {
    withTempDir { tempDirPath ->
      val compatDirectory = NioCompatFsDirectory(tempDirPath)

      assertEquals(
          expected = null,
          actual = compatDirectory.extract(UnixPath.Name.Literal("missing.txt")),
      )
    }
  }

  @Test
  fun test_extract_existing() = runTest {
    withTempDir { tempDirPath ->
      val fileName = UnixPath.Name.Literal("a.txt")
      val fileContent = "hello".encodeToByteString()
      val filePath = tempDirPath.resolve(fileName.name)

      filePath.createFile()
      filePath.writeBytes(fileContent.toByteArray())

      val compatDirectory = NioCompatFsDirectory(tempDirPath)
      val extractedFile = assertIs<NioCompatFsFile>(compatDirectory.extract(fileName))

      assertEquals(
          expected = fileContent,
          actual = extractedFile.read(),
      )
    }
  }

  @Test
  fun test_createFile() = runTest {
    withTempDir { tempDirPath ->
      val fileName = UnixPath.Name.Literal("created.txt")
      val fileContent = "created".encodeToByteString()
      val compatDirectory = NioCompatFsDirectory(tempDirPath)

      compatDirectory.createFile(
          name = fileName,
          initialContent = fileContent,
      )

      val filePath = tempDirPath.resolve(fileName.name)

      assertTrue(filePath.exists())
      assertEquals(
          expected = fileContent,
          actual = assertIs<NioCompatFsFile>(compatDirectory.extract(fileName)).read(),
      )
    }
  }

  @Test
  fun test_createDirectory() = runTest {
    withTempDir { tempDirPath ->
      val directoryName = UnixPath.Name.Literal("created")
      val compatDirectory = NioCompatFsDirectory(tempDirPath)

      compatDirectory.createDirectory(directoryName)

      val directoryPath = tempDirPath.resolve(directoryName.name)

      assertTrue(directoryPath.exists())
      assertTrue(directoryPath.isDirectory())
      assertIs<NioCompatFsDirectory>(compatDirectory.extract(directoryName))
    }
  }

  @Test
  fun test_delete_empty() = runTest {
    withTempDir { tempDirPath ->
      val compatDirectory =
          NioCompatFsDirectory(
              directoryPath = tempDirPath,
          )

      compatDirectory.delete()

      assertFalse(tempDirPath.exists())
    }
  }

  @Test
  fun test_delete_nonEmpty() = runTest {
    withTempDir { tempDirPath ->
      val singleFilePath = tempDirPath.resolve("a.txt")

      singleFilePath.createFile()

      val compatDirectory =
          NioCompatFsDirectory(
              directoryPath = tempDirPath,
          )

      assertFails { runTest { compatDirectory.delete() } }
    }
  }
}
