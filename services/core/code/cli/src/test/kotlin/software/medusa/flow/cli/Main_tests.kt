package software.medusa.flow.cli

import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Main_tests {
  @Test
  fun test_readTaskDescription_readsFromFile() {
    val tempFile = createTempFile(prefix = "task-", suffix = ".txt")

    try {
      Files.writeString(tempFile, "Solve this task.\n")

      assertEquals(
          expected = "Solve this task.\n",
          actual = readTaskDescription(source = tempFile.toString()),
      )
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  @Test
  fun test_createOpenAiLogRootPath_createsTempDirectory() {
    val logRootPath = createOpenAiLogRootPath()

    try {
      assertTrue(Files.exists(logRootPath))
      assertTrue(Files.isDirectory(logRootPath))
    } finally {
      logRootPath.toFile().deleteRecursively()
    }
  }
}
