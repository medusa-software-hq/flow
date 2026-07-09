package software.medusa.flow.server

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Status
import io.grpc.StatusException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import software.medusa.flow.v1.SessionEventKind as ProtoSessionEventKind
import software.medusa.flow.v1.WorkerServiceGrpcKt
import software.medusa.flow.v1.appendSessionEventRequest
import software.medusa.flow.v1.claimNextSessionRequest
import software.medusa.flow.v1.completeSessionRequest
import software.medusa.flow.v1.failSessionRequest
import software.medusa.flow.v1.heartbeatRequest
import software.medusa.flow.v1.sessionOrNull

/**
 * Exercises [WorkerServiceImpl] through a real in-process Armeria server and gRPC client: the
 * per-request auth stamping ([StubIdentityDecorator]), the [WorkerAuthorizer] gate, and the
 * `FAILED_PRECONDITION` state-machine guards, end to end — not against mocks.
 */
class WorkerServiceImpl_serverTests {
  companion object {
    private const val workerEmail = "worker-sa@project.iam.gserviceaccount.com"
    private const val userEmail = "person@example.com"
  }

  /** Stamps a fixed email as the [AuthenticatedUser] identity, mimicking a verified token. */
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

  private val store = InMemorySessionStore()
  private val workerAuthorizer = WorkerAuthorizer.allowlist(setOf(workerEmail))

  private fun startServerAs(
      email: String,
  ): Pair<com.linecorp.armeria.server.Server, WorkerServiceGrpcKt.WorkerServiceCoroutineStub> {
    val server =
        buildServer(
                originRegex = ".*",
                port = 0,
                auth = StubIdentityDecorator(email),
                counterStore = InMemoryCounterStore(),
                gitHubIssueStore = FakeGitHubIssueStore(),
                gitHubRepositoryStore = FakeGitHubRepositoryStore(),
                sessionStore = store,
                workerAuthorizer = workerAuthorizer,
            )
            .also { it.start().join() }

    val client =
        GrpcClients.newClient(
            "http://127.0.0.1:${server.activeLocalPort()}/",
            WorkerServiceGrpcKt.WorkerServiceCoroutineStub::class.java,
        )

    return server to client
  }

  private var server: com.linecorp.armeria.server.Server? = null

  @AfterTest
  fun tearDown() {
    server?.stop()?.join()
  }

  @Test
  fun `a user token calling WorkerService is rejected`() = runBlocking {
    val (srv, client) = startServerAs(userEmail)
    server = srv

    val failure =
        assertFailsWith<StatusException> { client.claimNextSession(claimNextSessionRequest {}) }

    assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
  }

  @Test
  fun `claim on an empty queue returns an absent session`() = runBlocking {
    val (srv, client) = startServerAs(workerEmail)
    server = srv

    val response = client.claimNextSession(claimNextSessionRequest {})

    assertNull(response.sessionOrNull)
  }

  @Test
  fun `claim, append event, heartbeat, complete round-trip works for an authorized worker`() =
      runBlocking {
        val (srv, client) = startServerAs(workerEmail)
        server = srv

        val created = store.create(repoFullName = "acme/app", taskMarkdown = "# Task", "u@x")

        val claimed = client.claimNextSession(claimNextSessionRequest {})
        assertEquals(created.id.id, claimed.session.id)

        client.appendSessionEvent(
            appendSessionEventRequest {
              sessionId = created.id.id
              kind = ProtoSessionEventKind.SESSION_EVENT_KIND_SCOUTING_ROUND
              message = "looking around"
            },
        )

        client.heartbeat(heartbeatRequest { sessionId = created.id.id })

        client.completeSession(
            completeSessionRequest {
              sessionId = created.id.id
              prUrl = "https://github.com/acme/app/pull/1"
            },
        )

        val result = store.get(created.id, afterSeq = 0)!!
        assertEquals(SessionState.Completed, result.session.state)
        assertEquals("https://github.com/acme/app/pull/1", result.session.prUrl)
        assertEquals(1, result.events.size)
      }

  @Test
  fun `completing an already-failed session returns FAILED_PRECONDITION and changes nothing`() =
      runBlocking {
        val (srv, client) = startServerAs(workerEmail)
        server = srv

        val created = store.create(repoFullName = "acme/app", taskMarkdown = "# Task", "u@x")
        store.claimNext()
        store.fail(created.id, "boom")

        val failure =
            assertFailsWith<StatusException> {
              client.completeSession(
                  completeSessionRequest {
                    sessionId = created.id.id
                    prUrl = "https://github.com/acme/app/pull/1"
                  },
              )
            }

        assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)

        val stillFailed = store.get(created.id, afterSeq = 0)!!.session
        assertEquals(SessionState.Failed, stillFailed.state)
        assertEquals("boom", stillFailed.failureSummary)
      }

  @Test
  fun `heartbeat and fail on a non-existent session both return FAILED_PRECONDITION`() =
      runBlocking {
        val (srv, client) = startServerAs(workerEmail)
        server = srv

        val heartbeatFailure =
            assertFailsWith<StatusException> {
              client.heartbeat(heartbeatRequest { sessionId = "missing" })
            }
        assertEquals(Status.Code.FAILED_PRECONDITION, heartbeatFailure.status.code)

        val failFailure =
            assertFailsWith<StatusException> {
              client.failSession(
                  failSessionRequest {
                    sessionId = "missing"
                    failureSummary = "x"
                  },
              )
            }
        assertEquals(Status.Code.FAILED_PRECONDITION, failFailure.status.code)
      }
}
