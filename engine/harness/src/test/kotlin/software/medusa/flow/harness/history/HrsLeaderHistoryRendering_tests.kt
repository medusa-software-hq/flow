package software.medusa.flow.harness.history

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.flow.harness.history.HrsLeaderHistoryRendering.renderLeaderHistory
import software.medusa.flow.harness.leadership.HrsTaskDefinition

class HrsLeaderHistoryRendering_tests {
  private val config = HrsChunkConfig(smallChunkSize = 3, bigChunkSize = 8)

  private fun logOf(
      delegationCount: Int,
  ): HrsDelegationLog =
      HrsDelegationLog(
          entries =
              (0 until delegationCount).map { k ->
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
              },
      )

  @Test
  fun `the last two small chunks render in full`() {
    // 9 delegations, 3 chunks: window = chunks 1,2 (delegations 3..8).
    val rendered = logOf(9).renderLeaderHistory(config).render()

    for (k in 3..8) assertTrue(
        rendered.contains("zzmark${k}zz"),
        "window delegation $k must render in full",
    )
  }

  @Test
  fun `a closed chunk with a stored summary renders the summary, not its delegations`() {
    val log =
        logOf(9).withSmallChunkSummary(chunkIndex = 0, summary = HrsChunkSummary("zzsummaryzz"))

    val rendered = log.renderLeaderHistory(config).render()

    assertTrue(rendered.contains("zzsummaryzz"), "the stored summary renders")
    for (k in 0..2) assertFalse(
        rendered.contains("zzmark${k}zz"),
        "summarized delegation $k must not render in full",
    )
  }

  @Test
  fun `a closed chunk without a summary degrades to its delegations in full`() {
    // No summary stored for chunk 0 -> it renders in full rather than blocking.
    val rendered = logOf(9).renderLeaderHistory(config).render()

    for (k in 0..2) assertTrue(
        rendered.contains("zzmark${k}zz"),
        "unsummarized delegation $k degrades to full",
    )
  }

  @Test
  fun `the leader history carries no file snapshots`() {
    // File content reaches the leader only through the exposed buffer, never history. There are no
    // code blocks in this rendering (the reports carry no snapshots).
    val rendered = logOf(12).renderLeaderHistory(config).render()

    assertFalse(rendered.contains("```"), "no code fences in the leader history view")
  }
}
