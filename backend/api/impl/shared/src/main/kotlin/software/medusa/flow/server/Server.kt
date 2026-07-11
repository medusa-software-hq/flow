package software.medusa.flow.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService

fun buildServer(
    originRegex: String,
    port: Int,
    auth: DecoratingHttpServiceFunction,
    counterStore: CounterStore,
    gitHubIssueStore: GitHubIssueStore,
    gitHubRepositoryStore: GitHubRepositoryStore,
    sessionStore: SessionStore,
    workerAuthorizer: WorkerAuthorizer,
    // Reconcile wiring. Defaulted so tests that don't exercise reconcile need not supply them; the
    // mains pass real stores (and, for in-memory/local, a *shared* backend so the pipeline and
    // outbox stores are atomic).
    issuePipelineStore: IssuePipelineStore = InMemoryIssuePipelineStore(),
    githubOutboxStore: GithubOutboxStore = InMemoryGithubOutboxStore(),
    gitHubIssueClient: GitHubIssueClient = FakeGitHubIssueClient(),
    gitHubPrClient: GitHubPrClient = FakeGitHubPrClient(),
    gitHubCandidateClient: GitHubCandidateClient = FakeGitHubCandidateClient(),
    reconcileAuthorizer: WorkerAuthorizer = WorkerAuthorizer.permissive,
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
            addService(CounterServiceImpl(counterStore))
            addService(GitHubServiceImpl(gitHubIssueStore, gitHubRepositoryStore))
            addService(SessionServiceImpl(sessionStore, issuePipelineStore))
            addService(WorkerServiceImpl(sessionStore, workerAuthorizer, issuePipelineStore))
            addService(ReconcileServiceImpl(reconciler, reconcileAuthorizer))
            enableUnframedRequests(true)
          }
          .build()

  return Server.builder()
      .apply {
        http(port)

        // Health check is unauthenticated (used by Cloud Run probes).
        service("/health", HealthCheckService.of())

        serviceUnder("/", grpcService.decorate(auth).decorate(cors))
      }
      .build()
}
