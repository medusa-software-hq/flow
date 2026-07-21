package software.medusa.flow.server

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.completeSessionRequest
import software.medusa.flow.v1.failSessionRequest

/**
 * Story 08: completing/failing an issue-linked session, through the real Worker gRPC service, also
 * advances its pipeline row — and a manual (unlinked) session leaves pipelines untouched.
 */
class WorkerServicePipelineLinkage_serverTests {
  private companion object {
    private const val workerEmail = "worker-sa@project.iam.gserviceaccount.com"
    private const val repo = "acme/app"
  }

  private class StubIdentityDecorator(
      private val email: String,
  ) : DecoratingHttpServiceFunction {
    override fun serve(
        delegate: HttpService,
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
      ctx.setAttr(AuthenticatedUser.emailAttributeKey, email)
      return delegate.serve(ctx, req)
    }
  }

  private val backend = InMemoryPipelineBackend()
  private val pipelines = InMemoryIssuePipelineStore(backend)
  private val outbox = InMemoryGithubOutboxStore(backend)
  private val sessions = InMemorySessionStore()

  private var server: com.linecorp.armeria.server.Server? = null

  @AfterTest
  fun tearDown() {
    server?.stop()?.join()
  }

  private fun startServer(): WorkerServiceGrpcKt.WorkerServiceCoroutineStub {
    val srv =
        buildServer(
                originRegex = ".*",
                port = 0,
                auth = StubIdentityDecorator(workerEmail),
                counterStore = InMemoryCounterStore(),
                gitHubIssueStore = FakeGitHubIssueStore(),
                gitHubRepositoryStore = FakeGitHubRepositoryStore(),
                sessionStore = sessions,
                workerAuthorizer = WorkerAuthorizer.allowlist(setOf(workerEmail)),
                issuePipelineStore = pipelines,
                githubOutboxStore = outbox,
            )
            .also { it.start().join() }
    server = srv
    return GrpcClients.newClient(
        "http://127.0.0.1:${srv.activeLocalPort()}/",
        WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java,
    )
  }

  /** A running, issue-linked session with an IN_PROGRESS pipeline. */
  private suspend fun linkedRunningSession(): Pair<SessionId, IssuePipelineId> {
    val session =
        sessions.create(repo, "# Issue 1\n\nbody", "flow-reconciler", engine = Engine.Unspecified)
    val pipeline =
        (pipelines.pick(repo, 1, "Issue 1", "https://x/1", session.id) as PickResult.Picked)
            .pipeline
    sessions.claimNext() // → RUNNING
    return session.id to pipeline.id
  }

  @Test
  fun `completing an issue-linked session advances its pipeline to PR_OPEN with label swap`() =
      runBlocking {
        val (sessionId, pipelineId) = linkedRunningSession()
        val client = startServer()

        client.completeSession(
            completeSessionRequest {
              this.sessionId = sessionId.id
              prUrl = "https://github.com/acme/app/pull/9"
            },
        )

        val pipeline = pipelines.get(pipelineId)!!
        assertEquals(IssuePipelineState.PrOpen, pipeline.state)
        assertEquals(9, pipeline.prNumber)

        // The label swap (remove flow:in-progress, add flow:pr-open) was enqueued.
        val actions = backend.outboxEntries.filter { it.issueNumber == 1 }.map { it.action }
        assertTrue(actions.contains(OutboxAction.RemoveLabel))
        assertTrue(actions.contains(OutboxAction.AddLabel))
      }

  @Test
  fun `failing an issue-linked session fails its pipeline (mutex still held)`() = runBlocking {
    val (sessionId, pipelineId) = linkedRunningSession()
    val client = startServer()

    client.failSession(
        failSessionRequest {
          this.sessionId = sessionId.id
          failureSummary = "attempts exhausted"
        },
    )

    assertEquals(IssuePipelineState.Failed, pipelines.get(pipelineId)!!.state)
    assertTrue(pipelines.isRepoBusy(repo))
  }

  @Test
  fun `completing a manual session (no pipeline) touches no pipelines`() = runBlocking {
    val session =
        sessions.create(repo, "# manual", "person@example.com", engine = Engine.Unspecified)
    sessions.claimNext()
    val client = startServer()

    client.completeSession(
        completeSessionRequest {
          this.sessionId = session.id.id
          prUrl = "https://github.com/acme/app/pull/1"
        },
    )

    assertTrue(pipelines.list(repo).isEmpty())
  }
}
