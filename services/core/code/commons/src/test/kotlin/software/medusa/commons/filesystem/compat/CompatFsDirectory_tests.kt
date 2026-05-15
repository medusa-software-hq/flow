package software.medusa.commons.filesystem.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsFile
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath

class CompatFsDirectory_tests {
  @Test
  fun test_extract_relativePath_nestedExisting() = runTest {
    val rootDirectory = MemoryCompatFsDirectory()

    val nestedDirectory =
        rootDirectory.createDirectory(
            UnixPath.Name.Literal("dist"),
        )

    nestedDirectory.createFile(
        name = UnixPath.Name.Literal("bundle.js"),
        initialContent = "console.log('hello')".encodeToByteString(),
    )

    val extractedEntity =
        rootDirectory.extract(
            RelativeUnixPath.of(
                UnixPath.Name.Literal("dist"),
                UnixPath.Name.Literal("bundle.js"),
            ),
        )

    val extractedFile = assertIs<MemoryCompatFsFile>(extractedEntity)

    assertEquals(
        expected = "console.log('hello')".encodeToByteString(),
        actual = extractedFile.read(),
    )
  }

  @Test
  fun test_extract_relativePath_throughFile_returnsNull() = runTest {
    val rootDirectory = MemoryCompatFsDirectory()

    rootDirectory.createFile(
        name = UnixPath.Name.Literal("dist"),
        initialContent = "content".encodeToByteString(),
    )

    val extractedEntity =
        rootDirectory.extract(
            RelativeUnixPath.of(
                UnixPath.Name.Literal("dist"),
                UnixPath.Name.Literal("bundle.js"),
            ),
        )

    assertNull(extractedEntity)
  }

  @Test
  fun test_copyRecursivelyTo_copiesNestedFiles_and_preservesUnrelatedTargetEntries() = runTest {
    val sourceDirectory = MemoryCompatFsDirectory()
    val targetDirectory = MemoryCompatFsDirectory()

    sourceDirectory.createFile(
        name = UnixPath.Name.Literal("root.txt"),
        initialContent = "source root".encodeToByteString(),
    )

    sourceDirectory
        .createDirectory(
            UnixPath.Name.Literal("nested"),
        )
        .createFile(
            name = UnixPath.Name.Literal("copied.txt"),
            initialContent = "nested source".encodeToByteString(),
        )

    targetDirectory.createFile(
        name = UnixPath.Name.Literal("root.txt"),
        initialContent = "target root".encodeToByteString(),
    )

    targetDirectory.createFile(
        name = UnixPath.Name.Literal("keep.txt"),
        initialContent = "keep me".encodeToByteString(),
    )

    sourceDirectory.copyRecursivelyTo(targetDirectory)

    val copiedRootFile =
        assertIs<MemoryCompatFsFile>(
            targetDirectory.extract(
                UnixPath.Name.Literal("root.txt"),
            ),
        )

    val copiedNestedFile =
        assertIs<MemoryCompatFsFile>(
            targetDirectory.extract(
                RelativeUnixPath.of(
                    UnixPath.Name.Literal("nested"),
                    UnixPath.Name.Literal("copied.txt"),
                ),
            ),
        )

    val preservedExtraFile =
        assertIs<MemoryCompatFsFile>(
            targetDirectory.extract(
                UnixPath.Name.Literal("keep.txt"),
            ),
        )

    assertEquals(
        expected = "source root".encodeToByteString(),
        actual = copiedRootFile.read(),
    )

    assertEquals(
        expected = "nested source".encodeToByteString(),
        actual = copiedNestedFile.read(),
    )

    assertEquals(
        expected = "keep me".encodeToByteString(),
        actual = preservedExtraFile.read(),
    )
  }

  @Test
  fun test_copyRecursivelyTo_replacesExistingDirectoryWithFile() = runTest {
    val sourceDirectory = MemoryCompatFsDirectory()
    val targetDirectory = MemoryCompatFsDirectory()

    sourceDirectory.createFile(
        name = UnixPath.Name.Literal("entry"),
        initialContent = "replacement file".encodeToByteString(),
    )

    targetDirectory
        .createDirectory(
            UnixPath.Name.Literal("entry"),
        )
        .createFile(
            name = UnixPath.Name.Literal("nested.txt"),
            initialContent = "nested".encodeToByteString(),
        )

    sourceDirectory.copyRecursivelyTo(targetDirectory)

    val copiedEntity =
        assertIs<MemoryCompatFsFile>(
            targetDirectory.extract(
                UnixPath.Name.Literal("entry"),
            ),
        )

    assertEquals(
        expected = "replacement file".encodeToByteString(),
        actual = copiedEntity.read(),
    )
  }

  @Test
  fun test_copyRecursivelyTo_replacesExistingFileWithDirectory() = runTest {
    val sourceDirectory = MemoryCompatFsDirectory()
    val targetDirectory = MemoryCompatFsDirectory()

    sourceDirectory
        .createDirectory(
            UnixPath.Name.Literal("entry"),
        )
        .createFile(
            name = UnixPath.Name.Literal("nested.txt"),
            initialContent = "replacement directory".encodeToByteString(),
        )

    targetDirectory.createFile(
        name = UnixPath.Name.Literal("entry"),
        initialContent = "old file".encodeToByteString(),
    )

    sourceDirectory.copyRecursivelyTo(targetDirectory)

    val copiedDirectory =
        assertIs<MemoryCompatFsDirectory>(
            targetDirectory.extract(
                UnixPath.Name.Literal("entry"),
            ),
        )

    val copiedFile =
        assertIs<MemoryCompatFsFile>(
            copiedDirectory.extract(
                UnixPath.Name.Literal("nested.txt"),
            ),
        )

    assertEquals(
        expected = "replacement directory".encodeToByteString(),
        actual = copiedFile.read(),
    )
  }
}
