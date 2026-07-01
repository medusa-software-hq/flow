package software.medusa.flow.virtual_editor.worktree_patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.filesystem.mutation.UfsFileMutation
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile

class VedFilePatch_tests {
  @Test
  fun `apply patches content of opened file`() {
    val file =
        VedOpenedFile.of(
            content = TxtFileContent(content = TxtBlock.of("old line", "second line")),
            timestamp = VedTimestamp.zero,
        )

    val patch =
        VedFilePatch(
            txtPatch =
                TxtPatch(
                    fragmentByOldLineIndexRange =
                        mapOf(
                            TxtLineIndexRange.of(startIndex = TxtLineIndex.First, length = 1) to
                                TxtPatch.Fragment(newContent = TxtBlock.of("new line")),
                        ),
                ),
        )

    val result = patch.patchOpenedFile(openedFile = file, timestamp = VedTimestamp.zero.next)

    val patchedFile = result.patchedEntity as VedOpenedFile

    assertEquals(
        TxtFileContent(content = TxtBlock.of("new line", "second line")),
        patchedFile.currentContent,
    )

    assertEquals(
        UfsFileMutation.Update(newContent = "new line\nsecond line\n".encodeToByteString()),
        result.entityMutation,
    )
  }

  @Test
  fun `apply throws on closed file`() {
    val patch =
        VedFilePatch(
            txtPatch =
                TxtPatch(
                    fragmentByOldLineIndexRange =
                        mapOf(
                            TxtLineIndexRange.of(startIndex = TxtLineIndex.First, length = 1) to
                                TxtPatch.Fragment(newContent = TxtBlock.of("new line")),
                        ),
                ),
        )

    assertFailsWith<IllegalStateException> {
      patch.patchFile(file = VedClosedFile, timestamp = VedTimestamp.zero)
    }
  }
}
