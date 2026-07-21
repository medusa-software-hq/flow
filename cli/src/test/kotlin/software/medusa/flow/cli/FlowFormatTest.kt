package software.medusa.flow.cli

import com.google.protobuf.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.flow.v1.Engine
import software.medusa.flow.v1.IssuePipelineState
import software.medusa.flow.v1.SessionState
import software.medusa.flow.v1.issuePipeline
import software.medusa.flow.v1.session

private fun ts(epochSecond: Long): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).build()

class FlowFormatTest {
  @Test
  fun `formatTimestamp renders minute precision in UTC and handles unset`() {
    // 2026-07-17T14:08:00Z
    assertEquals("2026-07-17 14:08 UTC", formatTimestamp(ts(1784297280)))
    assertEquals("—", formatTimestamp(null))
    assertEquals("—", formatTimestamp(Timestamp.getDefaultInstance()))
  }

  @Test
  fun `labels mirror the web app`() {
    assertEquals("Running", sessionStateLabel(SessionState.SESSION_STATE_RUNNING))
    assertEquals("Completed", sessionStateLabel(SessionState.SESSION_STATE_COMPLETED))
    assertEquals("Builtin", engineLabel(Engine.ENGINE_UNSPECIFIED))
    assertEquals("Builtin", engineLabel(Engine.ENGINE_BUILTIN))
    assertEquals("Claude Agent", engineLabel(Engine.ENGINE_CLAUDE))
    assertEquals(
        "Merge checks",
        pipelineStateLabel(IssuePipelineState.ISSUE_PIPELINE_STATE_AWAITING_MERGE_CHECKS),
    )
    assertEquals("Failed", pipelineStateLabel(IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED))
  }

  @Test
  fun `empty session and pipeline lists render friendly notes`() {
    assertEquals("No sessions yet.", formatSessionTable(emptyList()))
    assertEquals("No issue pipelines yet.", formatPipelineTable(emptyList()))
  }

  @Test
  fun `session table shows cost only for claude, and links repo issue`() {
    val table =
        formatSessionTable(
            listOf(
                session {
                  id = "s-1"
                  repoFullName = "acme/app"
                  state = SessionState.SESSION_STATE_COMPLETED
                  engine = Engine.ENGINE_CLAUDE
                  totalCostUsd = 0.0123
                  createdBy = "user@medusa.software"
                  prUrl = "https://github.com/acme/app/pull/7"
                  issueNumber = 12
                  createdAt = ts(1784297280)
                },
                session {
                  id = "s-2"
                  repoFullName = "acme/lib"
                  state = SessionState.SESSION_STATE_RUNNING
                  engine = Engine.ENGINE_BUILTIN
                },
            ),
        )
    val lines = table.lines()
    assertTrue(lines[0].startsWith("ID"))
    assertTrue(lines[1].contains("acme/app #12"))
    assertTrue(lines[1].contains("Completed"))
    assertTrue(lines[1].contains("Claude Agent"))
    assertTrue(lines[1].contains("$0.0123"))
    assertTrue(lines[1].contains("https://github.com/acme/app/pull/7"))
    // A builtin, no-cost, no-PR row shows em dashes rather than a cost / PR.
    assertTrue(lines[2].contains("Builtin"))
    assertFalse(lines[2].contains("$"))
  }

  @Test
  fun `pipeline table flags cleared and github-sync state`() {
    val table =
        formatPipelineTable(
            listOf(
                issuePipeline {
                  repoFullName = "acme/app"
                  issueNumber = 5
                  issueTitle = "Fix the thing"
                  state = IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED
                  cleared = true
                  outboxStuck = true
                  sessionId = "s-9"
                  prUrl = "https://github.com/acme/app/pull/9"
                  updatedAt = ts(1784297280)
                },
            ),
        )
    val lines = table.lines()
    assertTrue(lines[0].startsWith("REPO"))
    assertTrue(lines[1].contains("#5 Fix the thing"))
    assertTrue(lines[1].contains("Failed"))
    assertTrue(lines[1].contains("cleared"))
    assertTrue(lines[1].contains("github-sync-behind"))
    assertTrue(lines[1].contains("s-9"))
  }
}
