package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubstub.FakeGitHubAppKey
import software.medusa.flow.githubstub.FakeGitHubServer
import software.medusa.flow.v1.ReconcileServiceGrpcKt
import software.medusa.flow.v1.reconcileRequest

/**
 * Story-02 headline: the reconcile loop driven through the real RPC surface against the story-01
 * [FakeGitHubServer] with the *real* GitHub clients (App-token dance, REST + GraphQL) — reproducing
 * M2's "manual test against a scratch repo" for pick and observe, previously hand-verified.
 */
class ReconcileAgainstStub_componentTests {
  private val schedulerEmail = "scheduler-sa@project.iam.gserviceaccount.com"
  private val repo = "acme/app"

  private val stub = FakeGitHubServer().start()
  private val backend = InMemoryPipelineBackend()
  private val pipelines = InMemoryIssuePipelineStore(backend)
  private val outbox = InMemoryGithubOutboxStore(backend)
  private val sessions = InMemorySessionStore()

  private lateinit var server: Server
  private lateinit var reconcileClient: ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub

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

  @BeforeTest
  fun setUp() {
    val appClient =
        GitHubAppClient(
            GitHubAppConfig("id", FakeGitHubAppKey.pkcs8Pem, "acme", "app"),
            webClient = WebClient.of(stub.baseUrl),
        )
    server =
        buildServer(
                originRegex = ".*",
                port = 0,
                auth = StubIdentityDecorator(schedulerEmail),
                gitHubIssueStore = FakeGitHubIssueStore(),
                gitHubRepositoryStore = FakeGitHubRepositoryStore(),
                sessionStore = sessions,
                workerAuthorizer = WorkerAuthorizer.permissive,
                issuePipelineStore = pipelines,
                githubOutboxStore = outbox,
                // The reconciler's writes/reads go to GitHub over real HTTP → the stub.
                gitHubIssueClient = GitHubAppIssueClient(appClient),
                gitHubPrClient = GitHubAppPrClient(appClient),
                gitHubCandidateClient = GitHubAppCandidateClient(appClient),
                reconcileAuthorizer = WorkerAuthorizer.allowlist(setOf(schedulerEmail)),
            )
            .also { it.start().join() }
    reconcileClient =
        GrpcClients.newClient(
            "http://127.0.0.1:${server.activeLocalPort()}/",
            ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub::class.java,
        )
  }

  @AfterTest
  fun tearDown() {
    server.stop().join()
    stub.close()
  }

  private suspend fun reconcile() =
      reconcileClient.reconcile(reconcileRequest { repoFullName = repo })

  /** Reconciles repeatedly (bounded) until [done] — the reconciler converges over passes. */
  private suspend fun reconcileUntil(
      maxPasses: Int = 15,
      done: () -> Boolean,
  ) {
    repeat(maxPasses) {
      if (done()) return
      reconcile()
    }
    check(done()) { "condition not reached after $maxPasses reconcile passes" }
  }

  @Test
  fun `reconcile picks the oldest unblocked ready issue and projects flow-in-progress to GitHub`() =
      runBlocking {
        stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready"))
        stub.seedIssue(repo, 2, "B", labels = setOf("flow:ready"), blockedBy = listOf(1))

        reconcile()

        // A pipeline for the unblocked A only (mutex + blocked-by, via the real candidate client).
        val live = pipelines.listLive()
        assertEquals(1, live.size)
        assertEquals(1, live.single().issueNumber)

        // The in-progress label was projected to the stub over real HTTP (outbox drained).
        assertTrue("flow:in-progress" in stub.issue(repo, 1).labels, "A labeled in-progress")
        assertFalse(
            "flow:in-progress" in stub.issue(repo, 2).labels,
            "B untouched (blocked + mutex)",
        )
      }

  @Test
  fun `reconcile stamps the session engine from a flow-engine label seen over real GraphQL`() =
      runBlocking {
        stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready", "flow:engine=claude"))

        reconcile()

        val pipeline = pipelines.listLive().single()
        val session = sessions.get(pipeline.sessionId!!, afterSeq = 0)!!.session
        assertEquals(Engine.Claude, session.engine)
      }

  @Test
  fun `reconcile observes a merged, green PR, closes the issue in GitHub, then unblocks the dependent`() =
      runBlocking {
        stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready"))
        stub.seedIssue(repo, 2, "B", labels = setOf("flow:ready"), blockedBy = listOf(1))

        // Drive A's pipeline to AWAITING_MERGE_CHECKS behind a merged, green PR in the stub.
        val pipeline = pipelines.pickA()
        val prNumber = 100
        val sha = "mergecommitsha"
        stub.seedPullRequest(repo, prNumber)
        pipelines.markPrOpen(pipeline.id, prNumber, "$stubUrl/$repo/pull/$prNumber")
        pipelines.markAwaitingMergeChecks(pipeline.id, sha)
        stub.mergePullRequest(repo, prNumber, mergeCommitSha = sha)
        stub.setCheckConclusion(repo, sha, "Merge PR", conclusion = "SUCCESS")

        // The reconciler drains one outbox entry per issue per pass, so convergence takes several
        // passes (exactly as the scheduler + webhooks do it in production).
        reconcileUntil { !stub.issue(repo, 1).open }

        assertEquals(IssuePipelineState.Done, pipelines.get(pipeline.id)!!.state)
        assertFalse(stub.issue(repo, 1).open, "A closed in GitHub")
        assertTrue(
            stub.issue(repo, 1).comments.isNotEmpty(),
            "A annotated with the completion note",
        )

        // Now A is closed, B is unblocked → a later pass picks it and labels it in the stub.
        reconcileUntil { "flow:in-progress" in stub.issue(repo, 2).labels }
        assertEquals(2, pipelines.listLive().single().issueNumber)
      }

  @Test
  fun `readPriorityField reads the Priority Issue Field over real GraphQL`() = runBlocking {
    stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready"), priorityField = "Urgent")
    stub.seedIssue(repo, 2, "B", labels = setOf("flow:ready")) // unset

    val client = GitHubAppCandidateClient(candidateAppClient(), readPriorityField = true)
    val candidates = client.findReadyCandidates(repo).associateBy { it.number }

    assertEquals("Urgent", candidates.getValue(1).priorityField)
    assertEquals(null, candidates.getValue(2).priorityField)
  }

  @Test
  fun `readPriorityField degrades to label-only candidates when the stub schema doesn't support it`() =
      runBlocking {
        stub.issueFieldsSupported = false
        stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready", "priority:high"))

        val client = GitHubAppCandidateClient(candidateAppClient(), readPriorityField = true)
        val candidates = client.findReadyCandidates(repo)

        // The GraphQL `errors` response is caught and retried without the field fragment — the
        // candidate is still returned (with its label intact), not lost.
        assertEquals(1, candidates.single().number)
        assertEquals(null, candidates.single().priorityField)
        assertTrue("priority:high" in candidates.single().labels)
      }

  private fun candidateAppClient(): GitHubAppClient =
      GitHubAppClient(
          GitHubAppConfig("id", FakeGitHubAppKey.pkcs8Pem, "acme", "app"),
          webClient = WebClient.of(stub.baseUrl),
      )

  private val stubUrl: String
    get() = stub.baseUrl

  private suspend fun InMemoryIssuePipelineStore.pickA(): IssuePipeline {
    val result = pick(repo, 1, "A", "$stubUrl/$repo/issues/1", SessionId("sA"))
    return (result as PickResult.Picked).pipeline
  }
}
