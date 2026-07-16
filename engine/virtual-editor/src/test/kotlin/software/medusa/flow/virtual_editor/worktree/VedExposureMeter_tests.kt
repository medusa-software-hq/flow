package software.medusa.flow.virtual_editor.worktree

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.virtual_editor.VedTimestamp

class VedExposureMeter_tests {
  private fun openedFile(
      text: String,
      exposure: VedExposure,
  ): VedExpandedDirectory.LabeledEntity =
      VedExpandedDirectory.LabeledEntity(
          status = GitWorktreeEntity.Status.included,
          entity =
              VedOpenedFile.of(
                      content = TxtFileContent(content = TxtBlock.of(text)),
                      timestamp = VedTimestamp(1),
                  )
                  .withExposure(exposure),
      )

  private fun worktreeOf(
      vararg files: Pair<String, VedExpandedDirectory.LabeledEntity>,
  ): VedWorktree =
      VedWorktree(
          rootDirectory =
              VedExpandedDirectory(
                  labeledEntityByName =
                      files.associate { (name, entity) -> UfsName.Literal(name) to entity },
              ),
      )

  @Test
  fun `token approximation is chars over four`() {
    assertEquals(100, VedExposureMeter.approximateTokens(characterCount = 400))
    assertEquals(0, VedExposureMeter.approximateTokens(characterCount = 3))
  }

  @Test
  fun `count formatting is compact and locale-independent`() {
    assertEquals("512", VedExposureMeter.formatCount(512))
    assertEquals("999", VedExposureMeter.formatCount(999))
    assertEquals("1k", VedExposureMeter.formatCount(1000))
    assertEquals("14.2k", VedExposureMeter.formatCount(14200))
    assertEquals("14.2k", VedExposureMeter.formatCount(14250)) // truncates, not rounds
    assertEquals("20k", VedExposureMeter.formatCount(20000))
  }

  @Test
  fun `the meter counts only exposed files and their current content`() {
    val meter =
        VedExposureMeter.of(
            worktree =
                worktreeOf(
                    "a.txt" to openedFile(text = "a".repeat(400), exposure = VedExposure.Exposed),
                    "b.txt" to openedFile(text = "b".repeat(800), exposure = VedExposure.Exposed),
                    "hidden.txt" to
                        openedFile(text = "h".repeat(4000), exposure = VedExposure.Hidden),
                ),
        )

    assertEquals(2, meter.exposedFileCount)
    // (400 + 800) / 4 = 300; the hidden 4000-char file is not counted.
    assertEquals(300, meter.approximateTokenCount)
  }

  @Test
  fun `the meter renders a soft-budget header with a parameterized budget`() {
    val meter = VedExposureMeter(exposedFileCount = 7, approximateTokenCount = 14200)

    assertEquals(
        "Leader buffer: ~14.2k tokens across 7 exposed files (soft budget: 20k).",
        meter.render(softBudgetTokens = 20000),
    )
  }

  @Test
  fun `a single exposed file is rendered in the singular`() {
    val meter = VedExposureMeter(exposedFileCount = 1, approximateTokenCount = 42)

    assertEquals(
        "Leader buffer: ~42 tokens across 1 exposed file (soft budget: 8k).",
        meter.render(softBudgetTokens = 8000),
    )
  }
}
