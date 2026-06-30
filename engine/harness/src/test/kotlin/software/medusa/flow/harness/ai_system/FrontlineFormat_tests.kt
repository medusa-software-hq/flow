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
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.PatchCommand
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutCommand
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.dump
import software.medusa.flow.harness.ai_system.ScoutCommand_utils.load
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
  fun `a CONTINUE scout command round-trips through Markdown`() {
    val original: ScoutCommand =
        ScoutCommand.Continue(
            rationale = MdElement(blocks = listOf(MdBlock.Paragraph.of("Looking at the build."))),
            requestedAdjustment =
                VedWorktreeAdjustment(
                    rootDirectoryAdjustment =
                        VedDirectoryAdjustment.Dive(
                            childAdjustmentByName =
                                mapOf(
                                    UfsName.Literal("build.gradle.kts") to VedFileAdjustment.Open,
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
        )

    val reparsed = ScoutCommand.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = original, actual = reparsed)
  }

  @Test
  fun `a STOP scout command round-trips through Markdown`() {
    val original: ScoutCommand = ScoutCommand.Stop

    val reparsed = ScoutCommand.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = ScoutCommand.Stop, actual = reparsed)
  }

  @Test
  fun `the documented CONTINUE format parses into the expected adjustment`() {
    val markdownSource =
        """
        # EXPLORE

        I need to read the entry point and look into the source directory.

        - `/Main.kt` OPEN
        - `/src/` EXPAND
        """
            .trimIndent()

    val result =
        assertIs<ScoutCommand.Continue>(
            ScoutCommand.load(document = MdDocument.parse(markdownSource)),
        )

    val childAdjustmentByName =
        result.requestedAdjustment.rootDirectoryAdjustment.childAdjustmentByName

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
  fun `a deep path in CONTINUE expands into a chain of dives`() {
    val markdownSource =
        """
        # EXPLORE

        Reaching a file several directories down.

        - `/app/src/main/kotlin/org/example/App.kt` OPEN
        """
            .trimIndent()

    val result =
        assertIs<ScoutCommand.Continue>(
            ScoutCommand.load(document = MdDocument.parse(markdownSource)),
        )

    var dive = result.requestedAdjustment.rootDirectoryAdjustment

    listOf("app", "src", "main", "kotlin", "org", "example").forEach { segment ->
      dive =
          assertIs<VedDirectoryAdjustment.Dive>(
              dive.childAdjustmentByName.getValue(UfsName.Literal(segment)),
          )
    }

    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = dive.childAdjustmentByName[UfsName.Literal("App.kt")],
    )
  }

  @Test
  fun `flat absolute paths with shared prefixes are joined into one tree`() {
    val markdownSource =
        """
        # EXPLORE

        Opening the files relevant to the build across the project.

        - `/app/build.gradle.kts` OPEN
        - `/app/src/main/kotlin/org/example/App.kt` OPEN
        - `/app/src/test/kotlin/org/example/AppTest.kt` OPEN
        - `/settings.gradle.kts` OPEN
        - `/gradle/libs.versions.toml` OPEN
        """
            .trimIndent()

    val result =
        assertIs<ScoutCommand.Continue>(
            ScoutCommand.load(document = MdDocument.parse(markdownSource)),
        )

    val root = result.requestedAdjustment.rootDirectoryAdjustment

    assertEquals(
        expected = setOf("app", "gradle", "settings.gradle.kts"),
        actual = root.childAdjustmentByName.keys.map { it.content }.toSet(),
    )
    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = root.childAdjustmentByName[UfsName.Literal("settings.gradle.kts")],
    )

    // The shared `/app/` and `/app/src/` prefixes joined rather than clobbering each other.
    val app =
        assertIs<VedDirectoryAdjustment.Dive>(
            root.childAdjustmentByName.getValue(UfsName.Literal("app")),
        )
    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = app.childAdjustmentByName[UfsName.Literal("build.gradle.kts")],
    )

    val src =
        assertIs<VedDirectoryAdjustment.Dive>(
            app.childAdjustmentByName.getValue(UfsName.Literal("src")),
        )
    assertEquals(
        expected = setOf("main", "test"),
        actual = src.childAdjustmentByName.keys.map { it.content }.toSet(),
    )

    var dive = src
    listOf("main", "kotlin", "org", "example").forEach { segment ->
      dive =
          assertIs<VedDirectoryAdjustment.Dive>(
              dive.childAdjustmentByName.getValue(UfsName.Literal(segment)),
          )
    }
    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = dive.childAdjustmentByName[UfsName.Literal("App.kt")],
    )
  }

  @Test
  fun `a single-child dive chain round-trips as a deep path`() {
    val original: ScoutCommand =
        ScoutCommand.Continue(
            rationale = MdElement(blocks = listOf(MdBlock.Paragraph.of("Reaching deep."))),
            requestedAdjustment =
                VedWorktreeAdjustment(
                    rootDirectoryAdjustment =
                        VedDirectoryAdjustment.Dive(
                            childAdjustmentByName =
                                mapOf(
                                    UfsName.Literal("src") to
                                        VedDirectoryAdjustment.Dive(
                                            childAdjustmentByName =
                                                mapOf(
                                                    UfsName.Literal("main") to
                                                        VedDirectoryAdjustment.Dive(
                                                            childAdjustmentByName =
                                                                mapOf(
                                                                    UfsName.Literal("App.kt") to
                                                                        VedFileAdjustment.Open,
                                                                ),
                                                        ),
                                                ),
                                        ),
                                ),
                        ),
                ),
        )

    val reparsed = ScoutCommand.load(document = MdDocument.parse(original.dump().render()))

    assertEquals(expected = original, actual = reparsed)
  }

  @Test
  fun `a PATCH result round-trips through Markdown`() {
    val original =
        PatchCommand(
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

    val reparsed = PatchCommand.load(document = MdDocument.parse(original.dump().render()))

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

    val result = PatchCommand.load(document = MdDocument.parse(markdownSource))

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
