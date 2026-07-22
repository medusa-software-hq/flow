package software.medusa.flow.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun buildServer(
    originRegex: String,
    port: Int,
    auth: DecoratingHttpServiceFunction,
    gitHubIssueStore: GitHubIssueStore,
    gitHubRepositoryStore: GitHubRepositoryStore,
    sessionStore: SessionStore,
    workerAuthorizer: WorkerAuthorizer,
    // Worker fleet registry (M5). Defaulted to in-memory so tests and callers that don't exercise
    // worker liveness need not supply it; the mains pass the Postgres-backed store.
    workerStore: WorkerStore = InMemoryWorkerStore(),
    // Reconcile wiring. Defaulted so tests that don't exercise reconcile need not supply them; the
    // mains pass real stores (and, for in-memory/local, a *shared* backend so the pipeline and
    // outbox stores are atomic).
    issuePipelineStore: IssuePipelineStore = InMemoryIssuePipelineStore(),
    githubOutboxStore: GithubOutboxStore = InMemoryGithubOutboxStore(),
    gitHubIssueClient: GitHubIssueClient = FakeGitHubIssueClient(),
    gitHubPrClient: GitHubPrClient = FakeGitHubPrClient(),
    gitHubCandidateClient: GitHubCandidateClient = FakeGitHubCandidateClient(),
    reconcileAuthorizer: WorkerAuthorizer = WorkerAuthorizer.permissive,
    // GitHub webhook HMAC secret (story 10). Blank by default — the webhook route then fails every
    // request closed (401), since an unverifiable event must never trigger work. The mains supply
    // the real secret (Secret Manager on gcp, env on local).
    gitHubWebhookSecret: String = "",
    // When set, the reconciler only ever acts on repos owned by this org (every path is fenced).
    // The mains pass the GitHub App's owner; null (tests/local) disables the fence.
    reconcileOrgOwner: String? = null,
): Server {
  // Reconcile assembly — observe (06) and pick (07) are both real now.
  val reconciler =
      Reconciler(
          pipelineStore = issuePipelineStore,
          outboxStore = githubOutboxStore,
          dispatcher = OutboxDispatcher(githubOutboxStore, gitHubIssueClient),
          observer = ReconcileObserver(issuePipelineStore, sessionStore, gitHubPrClient),
          picker = ReconcilePicker(issuePipelineStore, sessionStore, gitHubCandidateClient),
          repoLock = InMemoryRepoLock(),
          // The scheduler discovers repos with `flow:ready` issues via one installation-wide
          // search.
          discoverReadyRepos = gitHubCandidateClient::findReposWithReadyIssues,
          orgOwner = reconcileOrgOwner,
      )

  // Webhook-triggered reconciles run detached (the endpoint answers 202 immediately). This scope
  // lives for the server's lifetime; SupervisorJob keeps one failing reconcile from cancelling the
  // rest. Correctness never depends on these completing — the scheduler is the backstop.
  val webhookReconcileScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
  val webhookService =
      GitHubWebhookService(
          webhookSecret = gitHubWebhookSecret,
          trigger = { repo -> webhookReconcileScope.launch { reconciler.reconcile(repo) } },
      )

  val cors =
      CorsService.builderForOriginRegex(originRegex)
          .apply {
            allowRequestMethods(HttpMethod.POST, HttpMethod.OPTIONS)
            allowRequestHeaders(
                HttpHeaderNames.AUTHORIZATION,
                HttpHeaderNames.CONTENT_TYPE,
                GrpcHeaderNames.X_GRPC_WEB,
                GrpcHeaderNames.X_USER_AGENT,
                GrpcHeaderNames.GRPC_TIMEOUT,
                GrpcHeaderNames.CONNECT_PROTOCOL_VERSION,
                GrpcHeaderNames.CONNECT_TIMEOUT_MS,
            )
            exposeHeaders(
                GrpcHeaderNames.GRPC_STATUS,
                GrpcHeaderNames.GRPC_MESSAGE,
                HttpHeaderNames.CONTENT_TYPE,
            )
          }
          .newDecorator()

  val grpcService =
      GrpcService.builder()
          .apply {
            addService(GitHubServiceImpl(gitHubIssueStore, gitHubRepositoryStore))
            addService(SessionServiceImpl(sessionStore, issuePipelineStore))
            addService(
                WorkerServiceImpl(sessionStore, workerAuthorizer, issuePipelineStore, workerStore),
            )
            addService(PipelineServiceImpl(issuePipelineStore, githubOutboxStore, sessionStore))
            addService(ReconcileServiceImpl(reconciler, reconcileAuthorizer))
            enableUnframedRequests(true)
          }
          .build()

  return Server.builder()
      .apply {
        http(port)

        // Health check is unauthenticated (used by Cloud Run probes).
        service("/health", HealthCheckService.of())

        // GitHub webhook: outside the gRPC auth decorator (it self-authenticates via HMAC).
        service(GitHubWebhookService.path, webhookService)

        serviceUnder("/", grpcService.decorate(auth).decorate(cors))
      }
      .build()
}
