package software.medusa.flow.harness.history

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.flow.harness.history.HrsBranchJournalRendering.RenderingPolicy
import software.medusa.flow.harness.history.HrsBranchJournalRendering.renderAssistantJournal
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatOpenedFile
import software.medusa.flow.virtual_editor.flat_worktree.VedFlatWorktree

class HrsBranchJournalRendering_tests {
  private fun entry(
      k: Int,
  ): HrsDelegationEntry =
      HrsDelegationEntry(
          taskDefinition = HrsTaskDefinition(markdown = "zzmark${k}zz"),
          report =
              HrsDelegationReport(
                  outcome = HrsDelegationOutcome.Done,
                  narrative = "did $k",
                  filesTouched = "- /f$k",
                  bufferChanges = "none",
                  checksSummary = "green",
              ),
      )

  private fun snapshot(
      path: String,
      text: String,
      t: Int,
  ): VedFlatOpenedFile =
      VedFlatOpenedFile(
          path = UfsAbsolutePath.parse(path).toLiteral()!!,
          content = TxtFileContent(content = TxtBlock.of(text)),
          modificationTimestamp = VedTimestamp(t),
      )

  private val log = HrsDelegationLog(entries = listOf(entry(0), entry(1)))

  // /a.txt: OLDVERSION at t=0, NEWVERSION at t=1; /b.txt: BONLY at t=1.
  private val flatWorktree =
      VedFlatWorktree(
          openedFiles =
              listOf(
                  snapshot("/a.txt", "OLDVERSION", t = 0),
                  snapshot("/a.txt", "NEWVERSION", t = 1),
                  snapshot("/b.txt", "BONLY", t = 1),
              ),
      )

  @Test
  fun `the full journal places each net snapshot in its delegation's segment`() {
    val rendered = log.renderAssistantJournal(flatWorktree, policy = RenderingPolicy.Full).render()

    assertTrue(rendered.contains("zzmark0zz"))
    assertTrue(rendered.contains("zzmark1zz"))
    assertTrue(rendered.contains("OLDVERSION"), "t=0 snapshot present")
    assertTrue(rendered.contains("NEWVERSION"), "t=1 snapshot present")
    assertTrue(rendered.contains("BONLY"), "t=1 second file present")
  }

  @Test
  fun `the B1 fallback drops per-segment snapshots and shows only latest versions`() {
    val rendered =
        log.renderAssistantJournal(flatWorktree, policy = RenderingPolicy.LatestVersionsOnly)
            .render()

    // Task/report pairs still there.
    assertTrue(rendered.contains("zzmark0zz"))
    assertTrue(rendered.contains("zzmark1zz"))

    // Superseded version gone; only the latest of each open file survives.
    assertFalse(rendered.contains("OLDVERSION"), "the superseded t=0 version must not render in B1")
    assertTrue(rendered.contains("NEWVERSION"), "the latest version of /a.txt renders")
    assertTrue(rendered.contains("BONLY"), "the latest version of /b.txt renders")
  }

  @Test
  fun `an empty journal renders a placeholder`() {
    val rendered =
        HrsDelegationLog()
            .renderAssistantJournal(VedFlatWorktree(openedFiles = emptyList()))
            .render()

    assertTrue(rendered.contains("empty"))
  }
}
