package software.medusa.flow.virtual_editor.worktree

import kotlin.test.Test
import kotlin.test.assertTrue
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderFiles
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

class VedWorktree_renderingUtils_tests {
  private fun labeledOpenedFile(
      text: String,
      timestamp: VedTimestamp,
  ): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity =
              VedOpenedFile.of(
                  content = TxtFileContent(content = TxtBlock.of(text)),
                  timestamp = timestamp,
              ),
      )

  private fun worktreeOf(
      vararg files: Pair<String, VedExpandedDirectory.LabeledEntity>,
  ): VedWorktree =
      VedWorktree(
          rootDirectory =
              VedExpandedDirectory(
                  labeledEntityByName =
                      files.associate { (name, labeledEntity) ->
                        UfsName.Literal(name) to labeledEntity
                      },
              ),
      )

  private fun replaceFirstLinePatch(
      fileName: String,
      newLine: String,
  ): VedWorktreePatch =
      VedWorktreePatch(
          rootDirectoryPatch =
              VedDirectoryPatch(
                  childPatchByName =
                      mapOf(
                          UfsName.Literal(fileName) to
                              VedFilePatch(
                                  txtPatch =
                                      TxtPatch(
                                          fragmentByOldLineIndexRange =
                                              mapOf(
                                                  TxtLineIndexRange.of(
                                                      startIndex = TxtLineIndex.First,
                                                      length = 1,
                                                  ) to
                                                      TxtPatch.Fragment(
                                                          newContent = TxtBlock.of(newLine)
                                                      ),
                                              ),
                                      ),
                              ),
                      ),
              ),
      )

  @Test
  fun `patching a worktree keeps the earlier rendered files as a stable prefix`() {
    // Opening files and patching them happens at monotonically increasing timestamps, exactly as
    // the
    // real scouting/implementation loop advances its clock.
    val worktree =
        worktreeOf(
            "f1.txt" to labeledOpenedFile(text = "alpha", timestamp = VedTimestamp(0)),
            "f2.txt" to labeledOpenedFile(text = "beta", timestamp = VedTimestamp(1)),
        )

    val originalRender = worktree.renderFiles().render()

    val patchedWorktree =
        replaceFirstLinePatch(fileName = "f1.txt", newLine = "alpha-revised")
            .patchWorktree(worktree = worktree, timestamp = VedTimestamp(2))
            .patchedWorktree

    val patchedRender = patchedWorktree.renderFiles().render()

    // The patch appends f1.txt's new revision at the tail (its timestamp is the highest), so the
    // original render survives verbatim as a prefix — the property the flat worktree exists to
    // give.
    assertTrue(
        patchedRender.startsWith(originalRender),
        "Patching must only append a new file revision, keeping the earlier render as a prefix",
    )
    assertTrue(
        patchedRender.length > originalRender.length,
        "The patch should have appended a new revision",
    )
  }

  @Test
  fun `an empty worktree renders a no-files message`() {
    val worktree =
        VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

    assertTrue(worktree.renderFiles().render().contains("No files are open."))
  }
}
