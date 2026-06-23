package software.medusa.flow.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.asFilesystemEntity

class HrsRawWorktreePatch_tests {
  private fun openedFile(vararg lines: String): VedOpenedFile =
      VedOpenedFile(content = TxtFileContent(content = TxtBlock.of(*lines)))

  private fun labeled(entity: VedExpandedDirectory): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity = entity,
      )

  private fun labeled(entity: VedOpenedFile): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity = entity,
      )

  private fun worktreeOf(
      labeledEntityByName: Map<UfsName.Literal, VedExpandedDirectory.LabeledEntity>,
  ): VedWorktree = VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName))

  @Test
  fun `replaces the content of an opened root file`() {
    val baseWorktree =
        worktreeOf(mapOf(UfsName.Literal("Main.kt") to labeled(openedFile("old line"))))

    val rawPatch =
        HrsRawWorktreePatch(
            editedFiles =
                listOf(
                    HrsRawWorktreePatch.EditedFile(
                        path = "/Main.kt",
                        newContent = "new line 1\nnew line 2\n",
                    ),
                ),
        )

    val finalWorktree =
        rawPatch.toFullWorktreePatch(baseWorktree).apply(baseWorktree).patchedWorktree

    val patchedFile =
        finalWorktree.rootDirectory.labeledEntityByName.getValue(UfsName.Literal("Main.kt")).entity
            as VedOpenedFile

    assertEquals(
        expected = TxtBlock.of("new line 1", "new line 2"),
        actual = patchedFile.content.content,
    )
  }

  @Test
  fun `replaces the content of an opened nested file`() {
    val baseWorktree =
        worktreeOf(
            mapOf(
                UfsName.Literal("src") to
                    labeled(
                        VedExpandedDirectory(
                            labeledEntityByName =
                                mapOf(UfsName.Literal("Main.kt") to labeled(openedFile("old"))),
                        ),
                    ),
            ),
        )

    val rawPatch =
        HrsRawWorktreePatch(
            editedFiles =
                listOf(
                    HrsRawWorktreePatch.EditedFile(path = "/src/Main.kt", newContent = "new\n"),
                ),
        )

    val finalWorktree =
        rawPatch.toFullWorktreePatch(baseWorktree).apply(baseWorktree).patchedWorktree

    val subdirectory =
        finalWorktree.rootDirectory.labeledEntityByName.getValue(UfsName.Literal("src")).entity
            as VedExpandedDirectory
    val patchedFile =
        subdirectory.labeledEntityByName.getValue(UfsName.Literal("Main.kt")).entity
            as VedOpenedFile

    assertEquals(expected = TxtBlock.of("new"), actual = patchedFile.content.content)
  }

  @Test
  fun `the patched content is observable through the filesystem overlay`() = runBlocking {
    val baseWorktree = worktreeOf(mapOf(UfsName.Literal("Main.kt") to labeled(openedFile("old"))))

    val rawPatch =
        HrsRawWorktreePatch(
            editedFiles =
                listOf(HrsRawWorktreePatch.EditedFile(path = "/Main.kt", newContent = "patched\n")),
        )

    val finalWorktree =
        rawPatch.toFullWorktreePatch(baseWorktree).apply(baseWorktree).patchedWorktree

    val fileView =
        assertIs<UfsReadonlyFile>(
            finalWorktree.rootDirectory.asFilesystemEntity.extract(UfsName.Literal("Main.kt")),
        )

    assertEquals(expected = "patched\n".encodeToByteString(), actual = fileView.read())
  }

  @Test
  fun `editing a path that is not an opened file fails`() {
    val baseWorktree = worktreeOf(mapOf(UfsName.Literal("Main.kt") to labeled(openedFile("old"))))

    val rawPatch =
        HrsRawWorktreePatch(
            editedFiles =
                listOf(HrsRawWorktreePatch.EditedFile(path = "/Missing.kt", newContent = "x\n")),
        )

    assertFailsWith<IllegalArgumentException> { rawPatch.toFullWorktreePatch(baseWorktree) }
  }
}
