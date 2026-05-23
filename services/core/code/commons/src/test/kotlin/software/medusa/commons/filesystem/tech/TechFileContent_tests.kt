package software.medusa.commons.filesystem.tech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.ByteString
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsFile

class TechFileContent_tests {
  @Test
  fun `classifies valid utf8 file as text`() = runTest {
    val file = MemoryCompatFsFile(initialPath = ByteString("hello\nworld".encodeToByteArray()))

    val content = file.readTechContent()

    assertEquals(
        expected = TechFileContent.Code.parse(rawContent = "hello\nworld"),
        actual = content,
    )
  }

  @Test
  fun `classifies invalid utf8 file as binary`() = runTest {
    val file = MemoryCompatFsFile(initialPath = ByteString(byteArrayOf(0xC3.toByte(), 0x28)))

    val content = file.readTechContent()

    assertEquals(
        expected = TechFileContent.Binary,
        actual = content,
    )
  }
}
