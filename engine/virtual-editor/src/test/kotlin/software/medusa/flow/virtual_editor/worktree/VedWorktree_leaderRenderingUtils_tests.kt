package software.medusa.flow.virtual_editor.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree_leaderRenderingUtils.renderLeaderBoard
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderDirectoryTree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderFiles

class VedWorktree_leaderRenderingUtils_tests {
  private val exposedMarker = "ZZEXPOSEDZZ"
  private val hiddenMarker = "ZZHIDDENZZ"

  private fun content(
      text: String,
  ): TxtFileContent = TxtFileContent(content = TxtBlock.of(text))

  private fun labeled(
      file: VedEntity,
  ): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(status = GitWorktreeEntity.Status.included, entity = file)

  /**
   * `/exposed.txt` (opened at t=1, exposed), `/hidden.txt` (opened at t=2, edited at t=5, hidden),
   * `/closed.txt` (never opened).
   */
  private fun worktree(
      exposeFirst: Boolean,
  ): VedWorktree {
    val exposed =
        VedOpenedFile.of(content = content(exposedMarker), timestamp = VedTimestamp(1))
            .withExposure(if (exposeFirst) VedExposure.Exposed else VedExposure.Hidden)

    val hidden =
        VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(2))
            .update(newContent = content(hiddenMarker), timestamp = VedTimestamp(5))

    return VedWorktree(
        rootDirectory =
            VedExpandedDirectory(
                labeledEntityByName =
                    mapOf(
                        UfsName.Literal("exposed.txt") to labeled(exposed),
                        UfsName.Literal("hidden.txt") to labeled(hidden),
                        UfsName.Literal("closed.txt") to labeled(VedClosedFile),
                    ),
            ),
    )
  }

  @Test
  fun `the leader board shows exposed content, hidden stubs, closed names, and the meter`() {
    val board = worktree(exposeFirst = true).renderLeaderBoard(softBudgetTokens = 20000).render()

    // Exposed file: full content on the board.
    assertTrue(board.contains(exposedMarker), "exposed content must render")

    // Hidden file: a one-line stub — path + timestamps, but NOT its content.
    assertFalse(board.contains(hiddenMarker), "hidden content must not render")
    assertTrue(board.contains("hidden.txt"), "hidden file listed")
    assertTrue(board.contains("opened at t=2"), "stub shows the open timestamp")
    assertTrue(board.contains("edited at t=5"), "stub shows the edit timestamp")

    // Closed file: name only.
    assertTrue(board.contains("closed.txt"), "closed file listed by name")

    // Meter header with the parameterized budget.
    assertTrue(board.contains("Leader buffer:"), "meter header present")
    assertTrue(board.contains("soft budget: 20k"), "parameterized budget rendered")
  }

  @Test
  fun `hiding a file reduces it to a stub while the exposed content disappears`() {
    val hiddenBoard =
        worktree(exposeFirst = false).renderLeaderBoard(softBudgetTokens = 20000).render()

    // With the first file hidden too, neither file's content renders — both are stubs.
    assertFalse(hiddenBoard.contains(exposedMarker), "hidden file's content is gone")
    assertTrue(hiddenBoard.contains("exposed.txt"), "hidden file still listed as a stub")
    assertTrue(hiddenBoard.contains("opened at t=1"), "stub shows its open timestamp")
  }

  @Test
  fun `the assistant content view is identical regardless of exposure`() {
    // renderFiles (the assistant's file-content view) is exposure-agnostic — the round-trip
    // invariant that keeps the classic engine bit-identical.
    assertEquals(
        worktree(exposeFirst = false).renderFiles().render(),
        worktree(exposeFirst = true).renderFiles().render(),
    )
  }

  @Test
  fun `the assistant tree gains an exposed annotation only when a file is exposed`() {
    // Bit-identical for the classic engine (never exposes): the all-hidden tree labels opened
    // files "(opened)", never "(opened, exposed)". Match the label, not the bare word — the fixture
    // filename "exposed.txt" contains "exposed" too.
    assertFalse(
        worktree(exposeFirst = false).renderDirectoryTree().render().contains("opened, exposed")
    )
    assertTrue(
        worktree(exposeFirst = true).renderDirectoryTree().render().contains("opened, exposed")
    )
  }
}
