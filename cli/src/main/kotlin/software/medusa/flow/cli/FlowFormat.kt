package software.medusa.flow.cli

import com.google.protobuf.Timestamp
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import software.medusa.flow.v1.Engine
import software.medusa.flow.v1.IssuePipeline
import software.medusa.flow.v1.IssuePipelineState
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionEvent
import software.medusa.flow.v1.SessionEventKind
import software.medusa.flow.v1.SessionState

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private const val emDash = "—"

/**
 * A proto [Timestamp] → `2026-07-17 14:08`; unset (default instance) or zero → `—`.
 *
 * Pinned to match the web app's `formatTimestamp` (ISO date + 24h UTC, no `UTC` suffix) — see
 * `sessionDisplay.ts`. Keep the two in lockstep; a change to one without the other is a bug.
 */
fun formatTimestamp(timestamp: Timestamp?): String {
  if (timestamp == null || (timestamp.seconds == 0L && timestamp.nanos == 0)) return emDash
  return runCatching {
        Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
            .atZone(ZoneOffset.UTC)
            .format(timestampFormatter)
      }
      .getOrDefault(emDash)
}

/** e.g. 0.0123 → `$0.0123`. Mirrors the web app's cost column. */
fun formatCostUsd(costUsd: Double): String = "$" + "%.4f".format(costUsd)

/** Session state → the web app's human label. */
fun sessionStateLabel(state: SessionState): String =
    when (state) {
      SessionState.SESSION_STATE_UNSPECIFIED -> "Unknown"
      SessionState.SESSION_STATE_PENDING -> "Pending"
      SessionState.SESSION_STATE_RUNNING -> "Running"
      SessionState.SESSION_STATE_COMPLETED -> "Completed"
      SessionState.SESSION_STATE_FAILED -> "Failed"
      else -> "Unknown"
    }

/** Engine → the web app's human label (UNSPECIFIED and BUILTIN both read as "Builtin"). */
fun engineLabel(engine: Engine): String =
    when (engine) {
      Engine.ENGINE_UNSPECIFIED,
      Engine.ENGINE_BUILTIN -> "Builtin"
      Engine.ENGINE_CLAUDE -> "Claude Agent"
      else -> "Builtin"
    }

/** Issue-pipeline state → the web app's human label. */
fun pipelineStateLabel(state: IssuePipelineState): String =
    when (state) {
      IssuePipelineState.ISSUE_PIPELINE_STATE_UNSPECIFIED -> "Unknown"
      IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS -> "In progress"
      IssuePipelineState.ISSUE_PIPELINE_STATE_PR_OPEN -> "PR open"
      IssuePipelineState.ISSUE_PIPELINE_STATE_AWAITING_MERGE_CHECKS -> "Merge checks"
      IssuePipelineState.ISSUE_PIPELINE_STATE_DONE -> "Done"
      IssuePipelineState.ISSUE_PIPELINE_STATE_FAILED -> "Failed"
      else -> "Unknown"
    }

/** A fixed-width table with a header row; columns padded to their widest cell. */
internal fun renderTable(header: List<String>, rows: List<List<String>>): String {
  val widths = header.indices.map { c -> (rows.map { it[c].length } + header[c].length).max() }
  fun line(cells: List<String>) =
      cells.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd()
  return (listOf(line(header)) + rows.map { line(it) }).joinToString("\n")
}

/** The repo cell, carrying the linked issue number when the session came from an issue pipeline. */
private fun Session.repoCell(): String =
    if (issueNumber > 0) "$repoFullName #$issueNumber" else repoFullName

private fun Session.costCell(): String =
    if (engine == Engine.ENGINE_CLAUDE && hasTotalCostUsd()) formatCostUsd(totalCostUsd) else emDash

/** Newest-first sessions, formatted like the web app's sessions list. */
fun formatSessionTable(sessions: List<Session>): String {
  if (sessions.isEmpty()) return "No sessions yet."
  return renderTable(
      listOf("ID", "REPO", "STATE", "ENGINE", "COST", "CREATED", "CREATED BY", "PR"),
      sessions.map {
        listOf(
            it.id,
            it.repoCell(),
            sessionStateLabel(it.state),
            engineLabel(it.engine),
            it.costCell(),
            formatTimestamp(it.createdAt),
            it.createdBy.ifBlank { emDash },
            it.prUrl.ifBlank { emDash },
        )
      },
  )
}

/** A session event's kind → a short human label for the detail view's log. */
fun eventKindLabel(kind: SessionEventKind): String =
    when (kind) {
      SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING -> "Preparing workspace"
      SessionEventKind.SESSION_EVENT_KIND_HEALTH_GATE -> "Health gate"
      SessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND -> "Scouting"
      SessionEventKind.SESSION_EVENT_KIND_WORKSPACE_BRIEFING -> "Briefing"
      SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING -> "Planning"
      SessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT -> "Implementing"
      SessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK -> "Health check"
      SessionEventKind.SESSION_EVENT_KIND_PUBLISHING -> "Publishing"
      SessionEventKind.SESSION_EVENT_KIND_AGENT_ACTION -> "Agent"
      SessionEventKind.SESSION_EVENT_KIND_ENGINE_BANNER -> "Engine"
      SessionEventKind.SESSION_EVENT_KIND_RUN_COST -> "Cost"
      else -> "?"
    }

/** One session's detail plus its event log — what `flow sessions show <id>` prints. */
fun formatSessionDetail(session: Session, events: List<SessionEvent>): String {
  val lines = buildList {
    add("Session ${session.id}")
    add("  Repo:    ${session.repoCell()}")
    if (session.issueUrl.isNotBlank()) add("  Issue:   ${session.issueUrl}")
    add("  State:   ${sessionStateLabel(session.state)}")
    add("  Engine:  ${engineLabel(session.engine)}")
    add(
        "  Created: ${formatTimestamp(session.createdAt)} by ${session.createdBy.ifBlank { emDash }}",
    )
    if (session.prUrl.isNotBlank()) add("  PR:      ${session.prUrl}")
    if (session.engine == Engine.ENGINE_CLAUDE && session.hasTotalCostUsd()) {
      add("  Cost:    ${formatCostUsd(session.totalCostUsd)}")
    }
    if (session.failureSummary.isNotBlank()) {
      add("  Failure:")
      session.failureSummary.trim().lines().forEach { add("    $it") }
    }
    if (session.taskMarkdown.isNotBlank()) {
      add("")
      add("Task:")
      session.taskMarkdown.trim().lines().forEach { add("  $it") }
    }
    add("")
    if (events.isEmpty()) {
      add("Event log: (none)")
    } else {
      add("Event log:")
      events.forEach { event ->
        add("  [${formatTimestamp(event.createdAt)}] ${eventKindLabel(event.kind)}")
        event.message.trim().lines().filter { it.isNotBlank() }.forEach { add("      $it") }
      }
    }
    // Conclude the timeline from the terminal state so the feed never dead-ends on a phase-start
    // (a FAILED run otherwise reads as still "Publishing…"). The full failure detail stays in the
    // Failure block above; here we just close the timeline and name the phase it stopped in.
    val lastPhase = events.lastOrNull()?.let { " during ${eventKindLabel(it.kind)}" }.orEmpty()
    val prSuffix = if (session.prUrl.isNotBlank()) " — PR: ${session.prUrl}" else ""
    when (session.state) {
      SessionState.SESSION_STATE_COMPLETED -> add("  ✓ Completed$prSuffix")
      SessionState.SESSION_STATE_FAILED -> add("  ✗ Failed$lastPhase")
      else -> {}
    }
  }
  return lines.joinToString("\n")
}

/** The state cell, flagging cleared / GitHub-sync-behind the way the web badges do. */
private fun IssuePipeline.stateCell(): String {
  val flags = buildList {
    if (cleared) add("cleared")
    if (outboxStuck) add("github-sync-behind")
  }
  val base = pipelineStateLabel(state)
  return if (flags.isEmpty()) base else "$base (${flags.joinToString(", ")})"
}

/** Live + recent issue pipelines, newest-first, formatted like the web app's pipelines list. */
fun formatPipelineTable(pipelines: List<IssuePipeline>): String {
  if (pipelines.isEmpty()) return "No issue pipelines yet."
  return renderTable(
      listOf("REPO", "ISSUE", "STATE", "SESSION", "PR", "UPDATED"),
      pipelines.map {
        listOf(
            it.repoFullName,
            if (it.issueNumber > 0) "#${it.issueNumber} ${it.issueTitle}".trim() else emDash,
            it.stateCell(),
            it.sessionId.ifBlank { emDash },
            it.prUrl.ifBlank { emDash },
            formatTimestamp(it.updatedAt),
        )
      },
  )
}
