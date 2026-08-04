package software.medusa.flow.server

import io.grpc.Status
import software.medusa.flow.v1.AbortSessionRequest
import software.medusa.flow.v1.AbortSessionResponse
import software.medusa.flow.v1.CreateSessionRequest
import software.medusa.flow.v1.CreateSessionResponse
import software.medusa.flow.v1.GetSessionRequest
import software.medusa.flow.v1.GetSessionResponse
import software.medusa.flow.v1.ListSessionsRequest
import software.medusa.flow.v1.ListSessionsResponse
import software.medusa.flow.v1.SessionServiceGrpcKt
import software.medusa.flow.v1.abortSessionResponse
import software.medusa.flow.v1.createSessionResponse
import software.medusa.flow.v1.getSessionResponse
import software.medusa.flow.v1.listSessionsResponse

/**
 * User-facing [SessionService][SessionServiceGrpcKt]: create/list/get over the [SessionStore].
 *
 * Reads delegate to the store's lazy heartbeat expiry (a `RUNNING` session past its timeout reads
 * back as `FAILED`, "worker lost"). Runs behind the shared auth decorator; [CreateSession]'s
 * `created_by` is taken from the verified token via [AuthenticatedUser].
 */
class SessionServiceImpl(
    private val sessionStore: SessionStore,
    private val issuePipelineStore: IssuePipelineStore,
) : SessionServiceGrpcKt.SessionServiceCoroutineImplBase() {
  private companion object {
    // owner/name: two non-empty, slash-free, whitespace-free segments.
    private val repoFullNameRegex = Regex("""^[^/\s]+/[^/\s]+$""")

    private const val listLimit = 100
  }

  override suspend fun createSession(
      request: CreateSessionRequest,
  ): CreateSessionResponse {
    val repoFullName = request.repoFullName
    if (!repoFullNameRegex.matches(repoFullName)) {
      throw Status.INVALID_ARGUMENT.withDescription(
              "repo_full_name must be of the form owner/name",
          )
          .asRuntimeException()
    }

    val taskMarkdown = request.taskMarkdown
    if (taskMarkdown.isBlank()) {
      throw Status.INVALID_ARGUMENT.withDescription("task_markdown must not be empty")
          .asRuntimeException()
    }

    val createdBy =
        AuthenticatedUser.currentEmail()
            ?: throw Status.UNAUTHENTICATED.withDescription("No authenticated caller")
                .asRuntimeException()

    val session =
        sessionStore.create(
            repoFullName = repoFullName,
            taskMarkdown = taskMarkdown,
            createdBy = createdBy,
            engine = request.engine.toDomain(),
        )

    return createSessionResponse { this.session = session.toProto() }
  }

  override suspend fun listSessions(
      request: ListSessionsRequest,
  ): ListSessionsResponse {
    // The store expires stale sessions before reading.
    val sessions = sessionStore.list(limit = listLimit)

    return listSessionsResponse {
      this.sessions += sessions.map { it.toProto(issuePipelineStore.findBySessionId(it.id)) }
    }
  }

  override suspend fun getSession(
      request: GetSessionRequest,
  ): GetSessionResponse {
    val result =
        sessionStore.get(id = SessionId(request.id), afterSeq = request.afterSeq)
            ?: throw Status.NOT_FOUND.withDescription("No such session: ${request.id}")
                .asRuntimeException()

    val linkedPipeline = issuePipelineStore.findBySessionId(result.session.id)

    return getSessionResponse {
      session = result.session.toProto(linkedPipeline)
      events += result.events.map { it.toProto() }
    }
  }

  override suspend fun abortSession(
      request: AbortSessionRequest,
  ): AbortSessionResponse {
    val id = SessionId(request.id)

    when (sessionStore.abort(id)) {
      // Aborted now, or already aborted — both are success for an idempotent "stop".
      is GuardedResult.Applied,
      GuardedResult.Aborted -> Unit
      GuardedResult.PreconditionFailed ->
          throw Status.FAILED_PRECONDITION.withDescription(
                  "Session ${request.id} is not running, so it cannot be aborted",
              )
              .asRuntimeException()
    }

    // Re-read (no events) to return the now-ABORTED session; the worker learns via its write-acks.
    val session =
        sessionStore.get(id = id, afterSeq = Int.MAX_VALUE)?.session
            ?: throw Status.NOT_FOUND.withDescription("No such session: ${request.id}")
                .asRuntimeException()

    return abortSessionResponse {
      this.session = session.toProto(issuePipelineStore.findBySessionId(id))
    }
  }
}
