package software.medusa.flow.cli

import com.google.protobuf.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.flow.v1.Engine
import software.medusa.flow.v1.IssuePipelineState
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.v1.SessionState
import software.medusa.flow.v1.issuePipeline
import software.medusa.flow.v1.issuePipelineTransition
import software.medusa.flow.v1.session
import software.medusa.flow.v1.sessionEvent
import software.medusa.flow.v1.settings

private fun ts(epochSecond: Long): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).build()

class FlowFormatTest {
  @Test
  fun `formatTimestamp renders minute precision in UTC and handles unset`() {
    // 2026-07-17T14:08:00Z. Same ISO date + 24h UTC shape (no "UTC" suffix) as the web app's
    // formatTimestamp in sessionDisplay.ts — keep the two pinned together.
    assertEquals("2026-07-17 14:08", formatTimestamp(ts(1784297280)))
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
  fun `session detail concludes the timeline from the terminal state`() {
    val events =
        listOf(
            sessionEvent {
              kind = SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT
              message = "Implementation attempt 1 of 1"
              createdAt = ts(1784297280)
            },
            sessionEvent {
              kind = SessionEventKind.SESSION_EVENT_KIND_PUBLISHING
              message = "Publishing the result as a pull request"
              createdAt = ts(1784297340)
            },
        )

    val failed =
        formatSessionDetail(
            session {
              id = "s-fail"
              repoFullName = "acme/app"
              state = SessionState.SESSION_STATE_FAILED
              failureSummary = "Failed to publish the result:\n\n```\nboom\n```"
            },
            events,
        )
    // The feed no longer dead-ends on the PUBLISHING phase-start; it names where it stopped.
    assertTrue(failed.trimEnd().endsWith("✗ Failed during Publishing"), failed)

    val completed =
        formatSessionDetail(
            session {
              id = "s-ok"
              repoFullName = "acme/app"
              state = SessionState.SESSION_STATE_COMPLETED
              prUrl = "https://github.com/acme/app/pull/7"
            },
            events,
        )
    assertTrue(
        completed.trimEnd().endsWith("✓ Completed — PR: https://github.com/acme/app/pull/7"),
        completed,
    )
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
                  id = "p-1"
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
    assertTrue(lines[0].startsWith("ID"))
    // The pipeline id — distinct from the session id — is what `flow pipelines clear` wants, so it
    // must be present and copyable straight from `list`.
    assertTrue(lines[1].contains("p-1"))
    assertTrue(lines[1].contains("#5 Fix the thing"))
    assertTrue(lines[1].contains("Failed"))
    assertTrue(lines[1].contains("cleared"))
    assertTrue(lines[1].contains("github-sync-behind"))
    assertTrue(lines[1].contains("s-9"))
  }

  @Test
  fun `formatSettings renders auto-merge on and off`() {
    assertEquals("Auto-merge: off", formatSettings(settings { autoMerge = false }))
    assertEquals("Auto-merge: on", formatSettings(settings { autoMerge = true }))
  }

  @Test
  fun `pipeline transition line shows old to new state, session, and PR`() {
    val transition = issuePipelineTransition {
      pipeline = issuePipeline {
        id = "p-1"
        repoFullName = "acme/app"
        issueNumber = 5
        issueTitle = "Fix the thing"
        state = IssuePipelineState.ISSUE_PIPELINE_STATE_PR_OPEN
        sessionId = "s-9"
        prUrl = "https://github.com/acme/app/pull/9"
      }
      oldState = IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS
      observedAt = ts(1784297280)
    }

    val line = formatPipelineTransitionLine(transition)
    assertTrue(line.contains("acme/app"))
    assertTrue(line.contains("#5 Fix the thing"))
    assertTrue(line.contains("In progress → PR open"))
    assertTrue(line.contains("id=p-1"))
    assertTrue(line.contains("session=s-9"))
    assertTrue(line.contains("pr=https://github.com/acme/app/pull/9"))
  }

  @Test
  fun `a brand-new pipeline's transition reads as (new), not Unknown to X`() {
    val transition = issuePipelineTransition {
      pipeline = issuePipeline {
        id = "p-2"
        repoFullName = "acme/app"
        state = IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS
      }
      // oldState left unset (UNSPECIFIED): a pipeline observed for the first time.
    }

    assertTrue(formatPipelineTransitionLine(transition).contains("(new) → In progress"))
  }

  @Test
  fun `pipeline transition JSON is one well-formed object with old and new state`() {
    val transition = issuePipelineTransition {
      pipeline = issuePipeline {
        id = "p-1"
        repoFullName = "acme/app"
        issueNumber = 5
        state = IssuePipelineState.ISSUE_PIPELINE_STATE_PR_OPEN
        sessionId = "s-9"
        prUrl = "https://github.com/acme/app/pull/9"
      }
      oldState = IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS
      observedAt = ts(1784297280)
    }

    val json = pipelineTransitionJson(transition)
    assertTrue(json.contains("\"id\":\"p-1\""))
    assertTrue(json.contains("\"old_state\":\"ISSUE_PIPELINE_STATE_IN_PROGRESS\""))
    assertTrue(json.contains("\"new_state\":\"ISSUE_PIPELINE_STATE_PR_OPEN\""))
    assertTrue(json.contains("\"pr_url\":\"https://github.com/acme/app/pull/9\""))
  }

  @Test
  fun `session event line and JSON carry the message and kind`() {
    val event = sessionEvent {
      seq = 3
      kind = SessionEventKind.SESSION_EVENT_KIND_PUBLISHING
      message = "Publishing the result as a pull request"
      createdAt = ts(1784297280)
    }

    val line = formatSessionEventLine(event)
    assertTrue(line.contains("Publishing"))
    assertTrue(line.contains("Publishing the result as a pull request"))

    val json = sessionEventJson("s-1", event)
    assertTrue(json.contains("\"session_id\":\"s-1\""))
    assertTrue(json.contains("\"seq\":3"))
    assertTrue(json.contains("\"kind\":\"SESSION_EVENT_KIND_PUBLISHING\""))
  }
}
