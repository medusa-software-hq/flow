package software.medusa.flow.virtual_editor.worktree_adjustment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedCollapsedDirectory
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory.LabeledEntity
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class VedWorktreeAdjustment_tests {
  private val readmeName = UfsName.Literal("README.md")
  private val srcName = UfsName.Literal("src")
  private val mainName = UfsName.Literal("Main.kt")

  /** A git worktree with a `README.md` file and a `src/Main.kt` file. */
  private suspend fun gitWorktree(): GitWorktree {
    val root = UfsMemoryDirectory()

    root.createFile(name = readmeName, initialContent = "hello".encodeToByteString())

    root
        .createDirectory(name = srcName)
        .createFile(name = mainName, initialContent = "fun main() {}".encodeToByteString())

    return GitWorktree.load(repoDirectory = root)
  }

  private fun included(
      entity: VedEntity,
  ): LabeledEntity = LabeledEntity(status = GitWorktreeEntity.Status.included, entity = entity)

  @Test
  fun `dive opens a closed file and expands a collapsed directory`() = runBlocking {
    val editorWorktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory(
                    labeledEntityByName =
                        mapOf(
                            readmeName to included(VedClosedFile),
                            srcName to included(VedCollapsedDirectory),
                        ),
                ),
        )

    val adjustment =
        VedWorktreeAdjustment(
            rootDirectoryAdjustment =
                VedDirectoryAdjustment.Dive(
                    childAdjustmentByName =
                        mapOf(
                            readmeName to VedFileAdjustment.Open,
                            srcName to VedDirectoryAdjustment.Expand,
                        ),
                ),
        )

    val adjustedRoot =
        adjustment
            .adjust(
                gitWorktree = gitWorktree(),
                editorWorktree = editorWorktree,
                timestamp = VedTimestamp.zero,
            )
            .adjustedWorktree
            .rootDirectory

    val openedReadme =
        assertIs<VedOpenedFile>(adjustedRoot.labeledEntityByName.getValue(readmeName).entity)
    assertEquals(TxtBlock.of("hello"), openedReadme.currentContent.content)

    val expandedSrc =
        assertIs<VedExpandedDirectory>(adjustedRoot.labeledEntityByName.getValue(srcName).entity)
    // Expanding reveals the directory's children one level deep, still closed/collapsed.
    assertIs<VedClosedFile>(expandedSrc.labeledEntityByName.getValue(mainName).entity)
    assertEquals(setOf(mainName), expandedSrc.labeledEntityByName.keys)
  }
}
