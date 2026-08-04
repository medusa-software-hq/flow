package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Status
import io.grpc.StatusException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import software.medusa.flow.v1.ReconcileServiceGrpcKt
import software.medusa.flow.v1.reconcileRequest

/**
 * Exercises [ReconcileServiceImpl]'s allowlist gate end to end through a real in-process Armeria
 * server and gRPC client: the scheduler and worker SAs pass, a signed-in user is rejected.
 */
class ReconcileServiceImpl_serverTests {
  companion object {
    private const val schedulerEmail = "scheduler-sa@project.iam.gserviceaccount.com"
    private const val workerEmail = "worker-sa@project.iam.gserviceaccount.com"
    private const val userEmail = "person@example.com"
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

  private val reconcileAuthorizer = WorkerAuthorizer.allowlist(setOf(schedulerEmail, workerEmail))
  private val backend = InMemoryPipelineBackend()

  private fun startServerAs(
      email: String,
  ): Pair<
      com.linecorp.armeria.server.Server,
      ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub,
  > {
    val server =
        buildServer(
                originRegex = ".*",
                port = 0,
                auth = StubIdentityDecorator(email),
                gitHubIssueStore = FakeGitHubIssueStore(),
                gitHubRepositoryStore = FakeGitHubRepositoryStore(),
                sessionStore = InMemorySessionStore(),
                workerAuthorizer = WorkerAuthorizer.permissive,
                issuePipelineStore = InMemoryIssuePipelineStore(backend),
                githubOutboxStore = InMemoryGithubOutboxStore(backend),
                gitHubIssueClient = FakeGitHubIssueClient(),
                reconcileAuthorizer = reconcileAuthorizer,
            )
            .also { it.start().join() }

    val client =
        GrpcClients.newClient(
            "http://127.0.0.1:${server.activeLocalPort()}/",
            ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub::class.java,
        )

    return server to client
  }

  private var server: com.linecorp.armeria.server.Server? = null

  @AfterTest
  fun tearDown() {
    server?.stop()?.join()
  }

  @Test
  fun `a signed-in user is rejected with PERMISSION_DENIED`() = runBlocking {
    val (srv, client) = startServerAs(userEmail)
    server = srv

    val failure = assertFailsWith<StatusException> { client.reconcile(reconcileRequest {}) }
    assertEquals(Status.Code.PERMISSION_DENIED, failure.status.code)
  }

  @Test
  fun `the scheduler SA is accepted`() = runBlocking {
    val (srv, client) = startServerAs(schedulerEmail)
    server = srv

    // Empty response (nothing relevant), but crucially not a PERMISSION_DENIED.
    val response = client.reconcile(reconcileRequest {})
    assertEquals(0, response.repoSummariesCount)
  }

  @Test
  fun `the worker SA is accepted`() = runBlocking {
    val (srv, client) = startServerAs(workerEmail)
    server = srv

    val response = client.reconcile(reconcileRequest {})
    assertEquals(0, response.repoSummariesCount)
  }

  /**
   * The exact wire shape Cloud Scheduler produces (gcp-scheduler.tf): a plain POST to the gRPC
   * method path, `Content-Type: application/json`, empty-object body — handled by the server's
   * unframed-request support. This pins that contract so a wrong content type / path in the
   * scheduler config would fail here rather than silently every 3 minutes in production.
   */
  @Test
  fun `an unframed JSON POST -- what Cloud Scheduler sends -- reaches Reconcile`() = runBlocking {
    val (srv, _) = startServerAs(schedulerEmail)
    server = srv

    val response =
        WebClient.of("http://127.0.0.1:${srv.activeLocalPort()}/")
            .prepare()
            .post("/medusa.pipeline.v1.ReconcileService/Reconcile")
            .content(MediaType.JSON, "{}")
            .execute()
            .aggregate()
            .await()

    assertEquals(HttpStatus.OK, response.status())
  }
}
