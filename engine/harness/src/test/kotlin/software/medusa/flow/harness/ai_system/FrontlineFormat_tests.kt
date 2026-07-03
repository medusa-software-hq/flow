package software.medusa.flow.harness.ai_system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.ai_system.HrsRawWorktreePatch.FilePath
import software.medusa.flow.harness.ai_system.HrsRawWorktreePatch.FileWrite
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedDirectoryAdjustment
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment

/**
 * Deterministic checks of the raw, LLM-facing shapes the interpreters decode: [HrsRawScoutDecision]
 * and [HrsRawWorktreePatch]. They assert that a raw structured response maps onto the domain model
 * the driver acts on — without hitting a model.
 */
class FrontlineFormat_tests {
  @Test
  fun `a raw scout decision with paths builds a merged adjustment tree`() {
    val rawDecision =
        HrsRawScoutDecision(
            scoutingComplete = false,
            filesToOpen = listOf("/build.gradle.kts", "/src/Main.kt"),
            directoriesToExpand = listOf("/src/resources"),
        )

    val continueDecision = assertIs<Decision.Continue>(rawDecision.toDecision())

    val rootChildren =
        continueDecision.requestedAdjustment.rootDirectoryAdjustment.childAdjustmentByName

    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = rootChildren[UfsName.Literal("build.gradle.kts")],
    )

    // `/src/Main.kt` and `/src/resources` join under a single `/src` dive.
    val srcDive = assertIs<VedDirectoryAdjustment.Dive>(rootChildren[UfsName.Literal("src")])

    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = srcDive.childAdjustmentByName[UfsName.Literal("Main.kt")],
    )
    assertEquals(
        expected = VedDirectoryAdjustment.Expand,
        actual = srcDive.childAdjustmentByName[UfsName.Literal("resources")],
    )
  }

  @Test
  fun `a raw scout decision marked complete stops scouting`() {
    val rawDecision =
        HrsRawScoutDecision(
            scoutingComplete = true,
            filesToOpen = emptyList(),
            directoriesToExpand = emptyList(),
        )

    assertEquals(expected = Decision.Stop, actual = rawDecision.toDecision())
  }

  @Test
  fun `an empty raw scout decision stops scouting even when not marked complete`() {
    val rawDecision =
        HrsRawScoutDecision(
            scoutingComplete = false,
            filesToOpen = emptyList(),
            directoriesToExpand = emptyList(),
        )

    assertEquals(expected = Decision.Stop, actual = rawDecision.toDecision())
  }

  @Test
  fun `an edit replaces the full content of an existing opened file`() {
    val baseWorktree = worktreeOf(fileName = "fib.lua", content = "line1\nline2\nline3\n")

    val patchedWorktree =
        rawPatch(edited = listOf(fileWrite("/fib.lua", "fixed1\nfixed2\n"))).applyTo(baseWorktree)

    val patchedFile =
        patchedWorktree.rootDirectory.labeledEntityByName
            .getValue(UfsName.Literal("fib.lua"))
            .entity as VedOpenedFile

    assertEquals(expected = "fixed1\nfixed2\n", actual = patchedFile.currentContent.content.dump())
  }

  @Test
  fun `a creation writes a new file that does not exist yet`() {
    val baseWorktree = worktreeOf(fileName = "Existing.kt", content = "existing\n")

    val patchedWorktree =
        rawPatch(
                created =
                    listOf(fileWrite("/src/test/NewTest.kt", "package test\n\nclass NewTest\n"))
            )
            .applyTo(baseWorktree)

    val createdFile =
        patchedWorktree.rootDirectory.labeledEntityByName
            .getValue(UfsName.Literal("src"))
            .let { it.entity as VedExpandedDirectory }
            .labeledEntityByName
            .getValue(UfsName.Literal("test"))
            .let { it.entity as VedExpandedDirectory }
            .labeledEntityByName
            .getValue(UfsName.Literal("NewTest.kt"))
            .entity as VedOpenedFile

    assertEquals(
        expected = "package test\n\nclass NewTest\n",
        actual = createdFile.currentContent.content.dump(),
    )
  }

  @Test
  fun `a deletion removes an existing file`() {
    val baseWorktree = worktreeOf(fileName = "Obsolete.kt", content = "obsolete\n")

    val patchedWorktree = rawPatch(deleted = listOf(FilePath("/Obsolete.kt"))).applyTo(baseWorktree)

    assertEquals(
        expected = false,
        actual =
            patchedWorktree.rootDirectory.labeledEntityByName.containsKey(
                UfsName.Literal("Obsolete.kt"),
            ),
    )
  }

  @Test
  fun `editing a file that does not exist is rejected`() {
    val baseWorktree = worktreeOf(fileName = "Existing.kt", content = "existing\n")

    assertFailsWith<IllegalArgumentException> {
      rawPatch(edited = listOf(fileWrite("/Missing.kt", "x\n"))).toFullWorktreePatch(baseWorktree)
    }
  }

  @Test
  fun `creating a file that already exists is rejected`() {
    val baseWorktree = worktreeOf(fileName = "Existing.kt", content = "existing\n")

    assertFailsWith<IllegalArgumentException> {
      rawPatch(created = listOf(fileWrite("/Existing.kt", "x\n"))).toFullWorktreePatch(baseWorktree)
    }
  }

  @Test
  fun `deleting a file that does not exist is rejected`() {
    val baseWorktree = worktreeOf(fileName = "Existing.kt", content = "existing\n")

    assertFailsWith<IllegalArgumentException> {
      rawPatch(deleted = listOf(FilePath("/Missing.kt"))).toFullWorktreePatch(baseWorktree)
    }
  }

  private fun fileWrite(
      path: String,
      newContent: String,
  ): FileWrite = FileWrite(path = path, newContent = newContent)

  private fun rawPatch(
      edited: List<FileWrite> = emptyList(),
      created: List<FileWrite> = emptyList(),
      deleted: List<FilePath> = emptyList(),
  ): HrsRawWorktreePatch =
      HrsRawWorktreePatch(editedFiles = edited, createdFiles = created, deletedFiles = deleted)

  private fun HrsRawWorktreePatch.applyTo(
      baseWorktree: VedWorktree,
  ): VedWorktree =
      toFullWorktreePatch(baseWorktree = baseWorktree)
          .patchWorktree(worktree = baseWorktree, timestamp = VedTimestamp.zero.next)
          .patchedWorktree

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
                                      VedOpenedFile.of(
                                          content =
                                              TxtFileContent(content = TxtBlock.parse(content)),
                                          timestamp = VedTimestamp.zero,
                                      ),
                              ),
                      ),
              ),
      )
}
