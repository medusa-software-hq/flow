package software.medusa.flow.harness.ai_system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.text.TxtLineIndex
import software.medusa.commons.text.TxtLineIndexRange
import software.medusa.commons.text.TxtPatch
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutRequest
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingResult
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationResult
import software.medusa.flow.harness.ai_system.ScoutingResult_utils.dump
import software.medusa.flow.harness.ai_system.ScoutingResult_utils.load
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.dump
import software.medusa.flow.harness.ai_system.SolutionImplementationResult_utils.load
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedWorktreeAdjustment
import software.medusa.flow.virtual_editor.worktree_patch.VedDirectoryPatch
import software.medusa.flow.virtual_editor.worktree_patch.VedFilePatch
import software.medusa.flow.virtual_editor.worktree_patch.VedWorktreePatch

/**
 * Deterministic checks of the ad-hoc Markdown codecs that the frontline AI system relies on. They
 * render a model to Markdown, parse it back, and assert the round-trip is lossless — and that the
 * exact format documented to the model parses as intended.
 */
class FrontlineFormat_tests {
  @Test
  fun `a CONTINUE scouting result round-trips through Markdown`() {
    val original: ScoutingResult =
        ScoutingResult.Continued(
            scoutRequest =
                ScoutRequest(
                    rationale =
                        MdElement(blocks = listOf(MdBlock.Paragraph.of("Looking at the build."))),
                    requestedAdjustment =
                        VedWorktreeAdjustment(
                            rootDirectoryAdjustment =
                                VedDirectoryAdjustment.Dive(
                                    childAdjustmentByName =
                                        mapOf(
                                            UfsName.Literal("build.gradle.kts") to
                                                VedFileAdjustment.Open,
                                            UfsName.Literal("src") to
                                                VedDirectoryAdjustment.Dive(
                                                    childAdjustmentByName =
                                                        mapOf(
                                                            UfsName.Literal("Main.kt") to
                                                                VedFileAdjustment.Open,
                                                            UfsName.Literal("resources") to
                                                                VedDirectoryAdjustment.Expand,
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    val reparsed = ScoutingResult.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = original, actual = reparsed)
  }

  @Test
  fun `a STOP scouting result round-trips through Markdown`() {
    val original: ScoutingResult = ScoutingResult.Completed

    val reparsed = ScoutingResult.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = ScoutingResult.Completed, actual = reparsed)
  }

  @Test
  fun `the documented CONTINUE format parses into the expected adjustment`() {
    val markdownSource =
        """
        # CONTINUE

        I need to read the entry point and look into the source directory.

        - `/`
            - `Main.kt` OPEN
            - `src/` EXPAND
        """
            .trimIndent()

    val result =
        assertIs<ScoutingResult.Continued>(
            ScoutingResult.load(document = MdDocument.parse(markdownSource)),
        )

    val childAdjustmentByName =
        result.scoutRequest.requestedAdjustment.rootDirectoryAdjustment.childAdjustmentByName

    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = childAdjustmentByName[UfsName.Literal("Main.kt")],
    )
    assertEquals(
        expected = VedDirectoryAdjustment.Expand,
        actual = childAdjustmentByName[UfsName.Literal("src")],
    )
  }

  @Test
  fun `a PATCH result round-trips through Markdown`() {
    val original =
        SolutionImplementationResult(
            solutionPatch =
                worktreePatchOf(
                    fileName = "Main.kt",
                    fragmentByOldLineIndexRange =
                        mapOf(
                            TxtLineIndexRange.empty(startIndex = TxtLineIndex.ofOneBased(1)) to
                                TxtPatch.Fragment(newContent = TxtBlock.of("import a.b.C")),
                            TxtLineIndexRange.of(
                                startIndex = TxtLineIndex.ofOneBased(4),
                                length = 2,
                            ) to TxtPatch.Fragment.Empty,
                            TxtLineIndexRange.of(
                                startIndex = TxtLineIndex.ofOneBased(8),
                                length = 1,
                            ) to TxtPatch.Fragment(newContent = TxtBlock.of("    return 13")),
                        ),
                ),
        )

    val reparsed =
        SolutionImplementationResult.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = original, actual = reparsed)
  }

  @Test
  fun `the documented PATCH format parses and applies onto the original file`() {
    val baseWorktree =
        worktreeOf(
            fileName = "fib.lua",
            content = "line1\nline2\nline3\nline4\n",
        )

    val markdownSource =
        """
        # PATCH

        ## `/fib.lua`

        ### INSERT BEFORE 1

        ```
        header
        ```

        ### DELETE 2-2

        ### UPDATE 4-4

        ```
        last
        ```
        """
            .trimIndent()

    val result = SolutionImplementationResult.load(document = MdDocument.parse(markdownSource))

    val patchedWorktree = result.solutionPatch.apply(worktree = baseWorktree).patchedWorktree

    val patchedFile =
        patchedWorktree.rootDirectory.labeledEntityByName
            .getValue(UfsName.Literal("fib.lua"))
            .entity as VedOpenedFile

    assertEquals(
        expected = "header\nline1\nline3\nlast\n",
        actual = patchedFile.content.content.dump(),
    )
  }

  private fun worktreePatchOf(
      fileName: String,
      fragmentByOldLineIndexRange: Map<TxtLineIndexRange, TxtPatch.Fragment>,
  ) =
      VedWorktreePatch(
          rootDirectoryPatch =
              VedDirectoryPatch(
                  childPatchByName =
                      mapOf(
                          UfsName.Literal(fileName) to
                              VedFilePatch(
                                  txtPatch =
                                      TxtPatch(
                                          fragmentByOldLineIndexRange = fragmentByOldLineIndexRange,
                                      ),
                              ),
                      ),
              ),
      )

  private fun worktreeOf(
      fileName: String,
      content: String,
  ): VedWorktree =
      VedWorktree(
          rootDirectory =
              VedExpandedDirectory(
                  labeledEntityByName =
                      mapOf(
                          UfsName.Literal(fileName) to
                              VedExpandedDirectory.LabeledEntity(
                                  status = GitWorktreeEntity.Status.included,
                                  entity =
                                      VedOpenedFile(
                                          content =
                                              TxtFileContent(content = TxtBlock.parse(content)),
                                      ),
                              ),
                      ),
              ),
      )
}
