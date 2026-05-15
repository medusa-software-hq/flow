package software.medusa.commons.filesystem.compat.impl.nio

import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsEntity_testUtils.withTempDir

class NioCompatFsFile_tests {
  @Test
  fun test_read() = runTest {
    withTempDir { tempDirPath ->
      val fileContent = "Hello, world!".encodeToByteString()

      val tempFilePath = tempDirPath.resolve("a.txt")

      tempFilePath.createFile()

      tempFilePath.writeBytes(
          fileContent.toByteArray(),
      )

      val compatFile =
          NioCompatFsFile(
              filePath = tempFilePath,
          )

      val readContent = compatFile.read()

      assertEquals(
          expected = fileContent,
          actual = readContent,
      )
    }
  }

  @Test
  fun test_write() = runTest {
    withTempDir { tempDirPath ->
      val fileContent = "Hello, world!!".encodeToByteString()

      val tempFilePath = tempDirPath.resolve("a.txt")

      tempFilePath.createFile()

      val compatFile =
          NioCompatFsFile(
              filePath = tempFilePath,
          )

      compatFile.write(fileContent)

      val readContent = compatFile.read()

      assertEquals(
          expected = fileContent,
          actual = readContent,
      )
    }
  }

  @Test
  fun test_delete() = runTest {
    withTempDir { tempDirPath ->
      val filePath = tempDirPath.resolve("a.txt")

      filePath.createFile()

      val compatDirectory =
          NioCompatFsFile(
              filePath = filePath,
          )

      compatDirectory.delete()

      assertFalse(
          filePath.exists(),
      )
    }
  }
}
