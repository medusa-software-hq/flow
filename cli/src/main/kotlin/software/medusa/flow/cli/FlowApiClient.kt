package software.medusa.flow.cli

import com.linecorp.armeria.client.grpc.GrpcClients
import software.medusa.flow.v1.GetSessionResponse
import software.medusa.flow.v1.IssuePipeline
import software.medusa.flow.v1.PipelineServiceGrpcKt
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.SessionServiceGrpcKt
import software.medusa.flow.v1.abortSessionRequest
import software.medusa.flow.v1.clearIssuePipelineRequest
import software.medusa.flow.v1.getSessionRequest
import software.medusa.flow.v1.listIssuePipelinesRequest
import software.medusa.flow.v1.listSessionsRequest

/**
 * gRPC client for the deployed Flow API — the human-facing surface the web app also uses
 * (`SessionService.ListSessions`/`GetSession`, `PipelineService.ListIssuePipelines`, and the
 * `ClearIssuePipeline` a human triggers to release a FAILED pipeline). Reads dominate; the only
 * mutation is `clearIssuePipeline`, matching the web app's Clear button. It attaches the caller's
 * Google user ID token as `Authorization: Bearer <token>` on every call, the same credential the
 * SPA sends; the API validates it against the configured OAuth client id (see
 * `GoogleIdTokenAuthDecorator`).
 *
 * The token is attached as a fixed header at build time rather than via refreshing call
 * credentials: a CLI invocation resolves a fresh token up front (via [FlowSession], refreshing if
 * needed) and makes a single call before exiting, so there's no long-lived stub to keep
 * re-authenticating.
 */
class FlowApiClient
private constructor(
    private val sessionStub: SessionServiceGrpcKt.SessionServiceCoroutineStub,
    private val pipelineStub: PipelineServiceGrpcKt.PipelineServiceCoroutineStub,
) {
  companion object {
    /** The `Authorization` header value for a bearer [idToken]. */
    internal fun bearerHeaderValue(idToken: String): String = "Bearer $idToken"

    fun create(apiUrl: String, idToken: String): FlowApiClient {
      val authorization = bearerHeaderValue(idToken)

      val sessionStub =
          GrpcClients.builder(apiUrl)
              .addHeader("Authorization", authorization)
              .build(SessionServiceGrpcKt.SessionServiceCoroutineStub::class.java)

      val pipelineStub =
          GrpcClients.builder(apiUrl)
              .addHeader("Authorization", authorization)
              .build(PipelineServiceGrpcKt.PipelineServiceCoroutineStub::class.java)

      return FlowApiClient(sessionStub = sessionStub, pipelineStub = pipelineStub)
    }
  }

  /** Newest-first sessions, as the web app's sessions list shows them. */
  suspend fun listSessions(): List<Session> =
      sessionStub.listSessions(listSessionsRequest {}).sessionsList

  /** One session with its full event log (the detail view). */
  suspend fun getSession(id: String): GetSessionResponse =
      sessionStub.getSession(getSessionRequest { this.id = id })

  /** Aborts a RUNNING session (the "stop"); returns the now-ABORTED session. */
  suspend fun abortSession(id: String): Session =
      sessionStub.abortSession(abortSessionRequest { this.id = id }).session

  /** Live + recent issue pipelines, newest-first, optionally filtered to one `owner/name` repo. */
  suspend fun listIssuePipelines(repoFullName: String? = null): List<IssuePipeline> =
      pipelineStub
          .listIssuePipelines(
              listIssuePipelinesRequest { repoFullName?.let { this.repoFullName = it } },
          )
          .pipelinesList

  /**
   * Clears a FAILED pipeline (the web app's Clear button), releasing the repo mutex so Flow can
   * re-pick the still-`flow:ready` issue. The API rejects any non-FAILED pipeline with
   * `FAILED_PRECONDITION`. Returns the updated (now-cleared) pipeline.
   */
  suspend fun clearIssuePipeline(id: String): IssuePipeline =
      pipelineStub.clearIssuePipeline(clearIssuePipelineRequest { this.id = id }).pipeline
}
