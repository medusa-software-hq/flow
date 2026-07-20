package software.medusa.flow.virtual_editor.worktree_adjustment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.filesystem.impl.memory.UfsMemoryDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory.LabeledEntity
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

class VedFileAdjustment_exposure_tests {
  private val readmeName = UfsName.Literal("README.md")

  /** A git worktree with a single `README.md` file. */
  private suspend fun gitWorktree(): GitWorktree {
    val root = UfsMemoryDirectory()
    root.createFile(name = readmeName, initialContent = "hello".encodeToByteString())
    return GitWorktree.load(repoDirectory = root)
  }

  private fun included(
      entity: VedEntity,
  ): LabeledEntity = LabeledEntity(status = GitWorktreeEntity.Status.included, entity = entity)

  private fun editorWorktreeWith(
      readme: VedEntity,
  ): VedWorktree =
      VedWorktree(
          rootDirectory =
              VedExpandedDirectory(labeledEntityByName = mapOf(readmeName to included(readme))),
      )

  private suspend fun applyToReadme(
      editorWorktree: VedWorktree,
      adjustment: VedFileAdjustment,
  ): VedEntity =
      VedWorktreeAdjustment(
              rootDirectoryAdjustment =
                  VedDirectoryAdjustment.Dive(
                      childAdjustmentByName = mapOf(readmeName to adjustment)
                  ),
          )
          .adjust(
              gitWorktree = gitWorktree(),
              editorWorktree = editorWorktree,
              timestamp = VedTimestamp.zero,
          )
          .adjustedWorktree
          .rootDirectory
          .labeledEntityByName
          .getValue(readmeName)
          .entity

  private val openedReadme: VedOpenedFile
    get() =
        VedOpenedFile.of(
            content = TxtFileContent(content = TxtBlock.of("hello")),
            timestamp = VedTimestamp.zero,
        )

  @Test
  fun `expose puts an opened file on the leader's board`() = runBlocking {
    val adjusted = applyToReadme(editorWorktreeWith(openedReadme), VedFileAdjustment.Expose)

    assertEquals(VedExposure.Exposed, assertIs<VedOpenedFile>(adjusted).exposure)
  }

  @Test
  fun `hide takes an opened file off the board`() = runBlocking {
    val adjusted =
        applyToReadme(
            editorWorktreeWith(openedReadme.withExposure(VedExposure.Exposed)),
            VedFileAdjustment.Hide,
        )

    assertEquals(VedExposure.Hidden, assertIs<VedOpenedFile>(adjusted).exposure)
  }

  @Test
  fun `exposing a file that is not open is a validation error`() = runBlocking {
    assertFailsWith<IllegalStateException> {
      applyToReadme(editorWorktreeWith(VedClosedFile), VedFileAdjustment.Expose)
    }
    Unit
  }
}
