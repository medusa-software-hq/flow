package software.medusa.commons.filesystem.compat

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsFile
import software.medusa.commons.paths.UnixPath

class FilesystemMutableCompatFsComboLogger_tests {
  @Test
  fun test_logs_file_and_directory_operations_into_memory_directory() = runTest {
    val logDirectory = MemoryCompatFsDirectory()
    val logger =
        FilesystemMutableCompatFsComboLogger(
            logDirectory = logDirectory,
            clock = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
        )

    logger.logWrite("hello".encodeToByteString())
    logger.logRead("world".encodeToByteString())
    logger.logIsExecutable(isExecutableStatus = true)
    logger.logDeleteFile()
    logger.logListEntries(
        listedEntries =
            listOf(
                ReadonlyCompatFsDirectory.Entry(
                    name = UnixPath.Name.Literal("a.txt"),
                    entity = MemoryCompatFsFile(),
                ),
                ReadonlyCompatFsDirectory.Entry(
                    name = UnixPath.Name.Literal("dir"),
                    entity = MemoryCompatFsDirectory(),
                ),
            ),
    )
    logger.logDeleteDirectory()

    assertBinaryLogFile(
        logDirectory = logDirectory,
        directoryName = "1234-write",
        fileName = "writtenContent.bin",
        expectedContent = "hello".encodeToByteString(),
    )
    assertBinaryLogFile(
        logDirectory = logDirectory,
        directoryName = "1234-read",
        fileName = "readContent.bin",
        expectedContent = "world".encodeToByteString(),
    )
    assertTextLogFile(
        logDirectory = logDirectory,
        directoryName = "1234-isExecutable",
        fileName = "status.json",
        expectedSubstring = "\"value\": true",
    )
    assertDirectoryExists(logDirectory = logDirectory, directoryName = "1234-deleteFile")
    assertTextLogFile(
        logDirectory = logDirectory,
        directoryName = "1234-listEntries",
        fileName = "listedEntries.json",
        expectedSubstring = "\"name\": \"a.txt\"",
    )
    assertDirectoryExists(logDirectory = logDirectory, directoryName = "1234-deleteDirectory")
  }

  private suspend fun assertDirectoryExists(
      logDirectory: MutableCompatFsDirectory,
      directoryName: String,
  ) {
    assertNotNull(
        assertIs<MutableCompatFsDirectory>(
            logDirectory.extract(UnixPath.Name.Literal(directoryName)),
        ),
    )
  }

  private suspend fun assertBinaryLogFile(
      logDirectory: MutableCompatFsDirectory,
      directoryName: String,
      fileName: String,
      expectedContent: ByteString,
  ) {
    val directory =
        assertNotNull(
            assertIs<MutableCompatFsDirectory>(
                logDirectory.extract(UnixPath.Name.Literal(directoryName)),
            ),
        )

    val file =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                directory.extract(UnixPath.Name.Literal(fileName)),
            ),
        )

    assertEquals(expected = expectedContent, actual = file.read())
  }

  private suspend fun assertTextLogFile(
      logDirectory: MutableCompatFsDirectory,
      directoryName: String,
      fileName: String,
      expectedSubstring: String,
  ) {
    val directory =
        assertNotNull(
            assertIs<MutableCompatFsDirectory>(
                logDirectory.extract(UnixPath.Name.Literal(directoryName)),
            ),
        )

    val file =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                directory.extract(UnixPath.Name.Literal(fileName)),
            ),
        )

    val content = file.read().decodeToString()

    kotlin.test.assertTrue(
        content.contains(expectedSubstring),
        "Expected <$content> to contain <$expectedSubstring>",
    )
  }
}
