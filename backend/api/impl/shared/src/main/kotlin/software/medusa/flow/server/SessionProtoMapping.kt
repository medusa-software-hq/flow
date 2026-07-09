package software.medusa.flow.server

import com.google.protobuf.Timestamp
import java.time.Instant
import software.medusa.flow.v1.Session as ProtoSession
import software.medusa.flow.v1.SessionEvent as ProtoSessionEvent
import software.medusa.flow.v1.SessionEventKind as ProtoSessionEventKind
import software.medusa.flow.v1.SessionState as ProtoSessionState
import software.medusa.flow.v1.session
import software.medusa.flow.v1.sessionEvent

/**
 * Conversions between the storage domain types ([Session] et al.) and the generated proto types.
 */
private fun Instant.toProtoTimestamp(): Timestamp =
    Timestamp.newBuilder().setSeconds(epochSecond).setNanos(nano).build()

private fun SessionState.toProto(): ProtoSessionState =
    when (this) {
      SessionState.Pending -> ProtoSessionState.SESSION_STATE_PENDING
      SessionState.Running -> ProtoSessionState.SESSION_STATE_RUNNING
      SessionState.Completed -> ProtoSessionState.SESSION_STATE_COMPLETED
      SessionState.Failed -> ProtoSessionState.SESSION_STATE_FAILED
    }

private fun SessionEventKind.toProto(): ProtoSessionEventKind =
    when (this) {
      SessionEventKind.WorkspacePreparing ->
          ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING
      SessionEventKind.HealthGate -> ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_GATE
      SessionEventKind.ScoutingRound -> ProtoSessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND
      SessionEventKind.WorkspaceBriefing ->
          ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_BRIEFING
      SessionEventKind.ImplementationPlanning ->
          ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING
      SessionEventKind.ImplementationAttempt ->
          ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT
      SessionEventKind.HealthCheck -> ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK
      SessionEventKind.Publishing -> ProtoSessionEventKind.SESSION_EVENT_KIND_PUBLISHING
    }

fun Session.toProto(): ProtoSession {
  val domainSession = this

  return session {
    id = domainSession.id.id
    repoFullName = domainSession.repoFullName
    taskMarkdown = domainSession.taskMarkdown
    state = domainSession.state.toProto()
    createdAt = domainSession.createdAt.toProtoTimestamp()
    createdBy = domainSession.createdBy
    // Proto3 strings default to empty; nulls collapse to "".
    prUrl = domainSession.prUrl.orEmpty()
    failureSummary = domainSession.failureSummary.orEmpty()
  }
}

fun SessionEvent.toProto(): ProtoSessionEvent {
  val domainEvent = this

  return sessionEvent {
    seq = domainEvent.seq
    createdAt = domainEvent.createdAt.toProtoTimestamp()
    kind = domainEvent.kind.toProto()
    message = domainEvent.message
  }
}

/**
 * Null for `SESSION_EVENT_KIND_UNSPECIFIED` (or an unrecognised value); the caller decides how to
 * react.
 */
fun ProtoSessionEventKind.toDomainOrNull(): SessionEventKind? =
    when (this) {
      ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_PREPARING ->
          SessionEventKind.WorkspacePreparing
      ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_GATE -> SessionEventKind.HealthGate
      ProtoSessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND -> SessionEventKind.ScoutingRound
      ProtoSessionEventKind.SESSION_EVENT_KIND_WORKSPACE_BRIEFING ->
          SessionEventKind.WorkspaceBriefing
      ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_PLANNING ->
          SessionEventKind.ImplementationPlanning
      ProtoSessionEventKind.SESSION_EVENT_KIND_IMPLEMENTATION_ATTEMPT ->
          SessionEventKind.ImplementationAttempt
      ProtoSessionEventKind.SESSION_EVENT_KIND_HEALTH_CHECK -> SessionEventKind.HealthCheck
      ProtoSessionEventKind.SESSION_EVENT_KIND_PUBLISHING -> SessionEventKind.Publishing
      ProtoSessionEventKind.SESSION_EVENT_KIND_UNSPECIFIED,
      ProtoSessionEventKind.UNRECOGNIZED -> null
    }
