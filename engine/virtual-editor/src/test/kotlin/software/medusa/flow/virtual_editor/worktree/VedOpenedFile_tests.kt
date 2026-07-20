package software.medusa.flow.virtual_editor.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.flow.virtual_editor.VedTimestamp

class VedOpenedFile_tests {
  private fun content(
      text: String,
  ): TxtFileContent = TxtFileContent(content = TxtBlock.of(text))

  @Test
  fun `opening a file defaults to hidden and is not yet edited`() {
    val file = VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(1))

    assertEquals(VedExposure.Hidden, file.exposure)
    assertEquals(VedTimestamp(1), file.openedTimestamp)
    assertEquals(VedTimestamp(1), file.lastEditedTimestamp)
    assertFalse(file.isEdited)
  }

  @Test
  fun `a later-timestamp update appends a version and marks the file edited`() {
    val edited =
        VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(1))
            .update(newContent = content("v2"), timestamp = VedTimestamp(3))

    assertEquals(2, edited.contentVersionHistory.contentVersions.size)
    assertEquals(content("v2"), edited.currentContent)
    assertEquals(VedTimestamp(1), edited.openedTimestamp)
    assertEquals(VedTimestamp(3), edited.lastEditedTimestamp)
    assertTrue(edited.isEdited)
  }

  @Test
  fun `an equal-timestamp update collapses to one net version per delegation`() {
    // Two writes within the same delegation (t=1) — only the settled version enters the journal.
    val collapsed =
        VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(1))
            .update(newContent = content("churn"), timestamp = VedTimestamp(1))
            .update(newContent = content("settled"), timestamp = VedTimestamp(1))

    assertEquals(1, collapsed.contentVersionHistory.contentVersions.size)
    assertEquals(content("settled"), collapsed.currentContent)
    assertFalse(collapsed.isEdited, "same-delegation churn is not an edit")
  }

  @Test
  fun `an earlier-timestamp update is rejected`() {
    val file = VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(3))

    assertFailsWith<IllegalArgumentException> {
      file.update(newContent = content("stale"), timestamp = VedTimestamp(2))
    }
  }

  @Test
  fun `a directly constructed history must have strictly increasing timestamps`() {
    assertFailsWith<IllegalArgumentException> {
      VedOpenedFile.ContentVersionHistory(
          contentVersions =
              listOf(
                  VedOpenedFile.ContentVersion(content("a"), VedTimestamp(2)),
                  VedOpenedFile.ContentVersion(content("b"), VedTimestamp(2)),
              ),
      )
    }
  }

  @Test
  fun `patching preserves exposure`() {
    val exposedThenPatched =
        VedOpenedFile.of(content = content("v1"), timestamp = VedTimestamp(1))
            .withExposure(VedExposure.Exposed)
            .update(newContent = content("v2"), timestamp = VedTimestamp(2))

    assertEquals(VedExposure.Exposed, exposedThenPatched.exposure)
    assertEquals(content("v2"), exposedThenPatched.currentContent)
  }
}
