package software.medusa.flow.server

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

fun main() {
  // The pipeline and outbox stores share one backend so transitions and their outbox entries are
  // applied atomically (mirrors the single Postgres transaction the Postgres pair shares).
  val pipelineBackend = InMemoryPipelineBackend()

  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          gitHubIssueStore = FakeGitHubIssueStore(),
          gitHubRepositoryStore = FakeGitHubRepositoryStore(),
          sessionStore = InMemorySessionStore(),
          workerAuthorizer = WorkerAuthorizer.permissive,
          issuePipelineStore = InMemoryIssuePipelineStore(pipelineBackend),
          githubOutboxStore = InMemoryGithubOutboxStore(pipelineBackend),
          gitHubIssueClient = FakeGitHubIssueClient(),
          gitHubPrClient = FakeGitHubPrClient(),
          gitHubCandidateClient = FakeGitHubCandidateClient(),
          reconcileAuthorizer = WorkerAuthorizer.permissive,
          // Local dev doesn't receive real GitHub webhooks; set GITHUB_WEBHOOK_SECRET to smoke-test
          // the endpoint with a hand-signed request. Empty → the route rejects everything (401).
          gitHubWebhookSecret = System.getenv("GITHUB_WEBHOOK_SECRET").orEmpty(),
      )
      .start()
      .join()
}
