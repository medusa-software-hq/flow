package software.medusa.flow.virtual_editor.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.path.UfsName

class VedEntity_filesystemView_tests {
  private fun openedFile(vararg lines: String): VedOpenedFile =
      VedOpenedFile(content = TxtFileContent(content = TxtBlock.of(*lines)))

  private fun labeled(entity: VedEntity): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity = entity,
      )

  @Test
  fun `exposes only opened files and expanded directories`() = runBlocking {
    val rootDirectory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    UfsName.Literal("a.txt") to labeled(openedFile("hello")),
                    UfsName.Literal("b.bin") to labeled(VedClosedFile),
                    UfsName.Literal("vendor") to labeled(VedCollapsedDirectory),
                    UfsName.Literal("src") to
                        labeled(
                            VedExpandedDirectory(
                                labeledEntityByName =
                                    mapOf(
                                        UfsName.Literal("Main.kt") to
                                            labeled(openedFile("fun main() {}")),
                                    ),
                            ),
                        ),
                ),
        )

    val view = rootDirectory.asFilesystemEntity

    assertEquals(
        expected = setOf(UfsName.Literal("a.txt"), UfsName.Literal("src")),
        actual = view.readIndex().childEntityByName.keys,
    )

    assertNull(view.extract(UfsName.Literal("b.bin")))
    assertNull(view.extract(UfsName.Literal("vendor")))
  }

  @Test
  fun `reads opened file content as LF-terminated bytes`() = runBlocking {
    val rootDirectory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    UfsName.Literal("a.txt") to labeled(openedFile("hello", "world")),
                ),
        )

    val openedFileView =
        assertIs<UfsReadonlyFile>(
            rootDirectory.asFilesystemEntity.extract(UfsName.Literal("a.txt"))
        )

    assertEquals(
        expected = "hello\nworld\n".encodeToByteString(),
        actual = openedFileView.read(),
    )
  }

  @Test
  fun `descends into nested expanded directories`() = runBlocking {
    val rootDirectory =
        VedExpandedDirectory(
            labeledEntityByName =
                mapOf(
                    UfsName.Literal("src") to
                        labeled(
                            VedExpandedDirectory(
                                labeledEntityByName =
                                    mapOf(
                                        UfsName.Literal("Main.kt") to
                                            labeled(openedFile("fun main() {}")),
                                    ),
                            ),
                        ),
                ),
        )

    val subdirectoryView =
        assertIs<UfsReadonlyDirectory>(
            rootDirectory.asFilesystemEntity.extract(UfsName.Literal("src")),
        )

    val nestedFileView =
        assertIs<UfsReadonlyFile>(subdirectoryView.extract(UfsName.Literal("Main.kt")))

    assertEquals(
        expected = "fun main() {}\n".encodeToByteString(),
        actual = nestedFileView.read(),
    )
  }
}
