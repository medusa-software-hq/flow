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
          counterStore = InMemoryCounterStore(),
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
      )
      .start()
      .join()
}
