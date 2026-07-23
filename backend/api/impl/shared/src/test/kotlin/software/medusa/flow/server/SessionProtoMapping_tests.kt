package software.medusa.flow.server

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.flow.v1.SessionEventKind as ProtoSessionEventKind
import software.medusa.flow.v1.SessionState as ProtoSessionState

class SessionProtoMapping_tests {
  @Test
  fun `session maps to proto with state, timestamp, and null strings collapsed to empty`() {
    val createdAt = Instant.parse("2026-01-02T03:04:05.000000006Z")

    val session =
        Session(
            id = SessionId("abc"),
            jobId = JobId("abc-job"),
            repoFullName = "acme/app",
            taskMarkdown = "# Task",
            state = SessionState.Running,
            createdAt = createdAt,
            createdBy = "u@x",
            claimedAt = createdAt,
            lastHeartbeatAt = createdAt,
            prUrl = null,
            failureSummary = null,
            engine = Engine.Unspecified,
        )

    val proto = session.toProto()

    assertEquals("abc", proto.id)
    assertEquals("acme/app", proto.repoFullName)
    assertEquals(ProtoSessionState.SESSION_STATE_RUNNING, proto.state)
    assertEquals(createdAt.epochSecond, proto.createdAt.seconds)
    assertEquals(createdAt.nano, proto.createdAt.nanos)
    assertEquals("u@x", proto.createdBy)
    assertEquals("", proto.prUrl)
    assertEquals("", proto.failureSummary)
  }

  @Test
  fun `completed and failed sessions carry their terminal fields`() {
    val base =
        Session(
            id = SessionId("x"),
            jobId = JobId("x-job"),
            repoFullName = "acme/app",
            taskMarkdown = "t",
            state = SessionState.Completed,
            createdAt = Instant.EPOCH,
            createdBy = "u@x",
            claimedAt = Instant.EPOCH,
            lastHeartbeatAt = Instant.EPOCH,
            prUrl = "https://pr/1",
            failureSummary = null,
            engine = Engine.Unspecified,
        )

    val completed = base.toProto()
    assertEquals(ProtoSessionState.SESSION_STATE_COMPLETED, completed.state)
    assertEquals("https://pr/1", completed.prUrl)

    val failed =
        base.copy(state = SessionState.Failed, prUrl = null, failureSummary = "boom").toProto()
    assertEquals(ProtoSessionState.SESSION_STATE_FAILED, failed.state)
    assertEquals("boom", failed.failureSummary)
  }

  @Test
  fun `event kinds map one-to-one to proto`() {
    val expected =
        mapOf(
            SessionEventKind.WorkspacePreparing to
                ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING,
            SessionEventKind.HealthGate to ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_GATE,
            SessionEventKind.ScoutingRound to
                ProtoSessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND,
            SessionEventKind.WorkspaceBriefing to
                ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_BRIEFING,
            SessionEventKind.ImplementationPlanning to
                ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING,
            SessionEventKind.ImplementationAttempt to
                ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT,
            SessionEventKind.HealthCheck to ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK,
            SessionEventKind.Publishing to ProtoSessionEventKind.SESSION_EVENT_KIND_PUBLISHING,
            SessionEventKind.AgentAction to ProtoSessionEventKind.SESSION_EVENT_KIND_AGENT_ACTION,
            SessionEventKind.EngineBanner to ProtoSessionEventKind.SESSION_EVENT_KIND_ENGINE_BANNER,
            SessionEventKind.RunCost to ProtoSessionEventKind.SESSION_EVENT_KIND_RUN_COST,
        )

    expected.forEach { (domain, proto) ->
      val event =
          SessionEvent(seq = 1, createdAt = Instant.EPOCH, kind = domain, message = "m").toProto()

      assertEquals(proto, event.kind)

      // And the reverse mapping round-trips.
      assertEquals(domain, proto.toDomainOrNull())
    }

    // Every domain kind is covered above (mirror of the exhaustive `when` in the mapping).
    assertEquals(SessionEventKind.entries.toSet(), expected.keys)
  }

  @Test
  fun `total cost is set on the proto only when known`() {
    val base =
        Session(
            id = SessionId("c"),
            jobId = JobId("c-job"),
            repoFullName = "acme/app",
            taskMarkdown = "t",
            state = SessionState.Completed,
            createdAt = Instant.EPOCH,
            createdBy = "u@x",
            claimedAt = Instant.EPOCH,
            lastHeartbeatAt = Instant.EPOCH,
            prUrl = "https://pr/1",
            failureSummary = null,
            engine = Engine.Claude,
        )

    assertEquals(false, base.toProto().hasTotalCostUsd())
    assertEquals(0.0421, base.copy(totalCostUsd = 0.0421).toProto().totalCostUsd)
  }

  @Test
  fun `a linked pipeline populates the session's issue fields and manual leaves them empty`() {
    val session =
        Session(
            id = SessionId("s1"),
            jobId = JobId("s1-job"),
            repoFullName = "acme/app",
            taskMarkdown = "# Task",
            state = SessionState.Running,
            createdAt = Instant.EPOCH,
            createdBy = "flow-reconciler",
            claimedAt = null,
            lastHeartbeatAt = null,
            prUrl = null,
            failureSummary = null,
            engine = Engine.Unspecified,
        )

    val pipeline =
        IssuePipeline(
            id = IssuePipelineId("p1"),
            repoFullName = "acme/app",
            issueNumber = 42,
            issueTitle = "Do the thing",
            issueUrl = "https://github.com/acme/app/issues/42",
            state = IssuePipelineState.InProgress,
            sessionId = SessionId("s1"),
            shadowSessionId = SessionId("s1-shadow"),
            prNumber = null,
            prUrl = null,
            mergeCommitSha = null,
            failureSummary = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            clearedAt = null,
        )

    val linked = session.toProto(pipeline)
    assertEquals(42, linked.issueNumber)
    assertEquals("https://github.com/acme/app/issues/42", linked.issueUrl)
    assertEquals("p1", linked.issuePipelineId)

    val manual = session.toProto(linkedPipeline = null)
    assertEquals(0, manual.issueNumber)
    assertEquals("", manual.issueUrl)
    assertEquals("", manual.issuePipelineId)
  }
}
