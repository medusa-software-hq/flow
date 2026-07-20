package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.server.Server
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubstub.FakeGitHubAppKey
import software.medusa.flow.githubstub.FakeGitHubServer

/**
 * Story-02: the GitHub webhook route as mounted by [buildServer] — HMAC-verified and *outside* the
 * gRPC auth decorator — drives a full delivery → reconcile → GitHub-write loop. A validly-signed
 * delivery (sent by the story-01 stub's signer) picks the ready issue; a bad signature is 401'd and
 * changes nothing. Previously hand-verified against the deployed API.
 */
class WebhookRoute_componentTests {
  private val secret = "webhook-s3cr3t"
  private val repo = "acme/app"

  private val stub = FakeGitHubServer().start()
  private val backend = InMemoryPipelineBackend()
  private val pipelines = InMemoryIssuePipelineStore(backend)

  private val server: Server
  private val serverUrl: String
    get() = "http://127.0.0.1:${server.activeLocalPort()}"

  init {
    val appClient =
        GitHubAppClient(
            GitHubAppConfig("id", FakeGitHubAppKey.pkcs8Pem, "acme", "app"),
            webClient = WebClient.of(stub.baseUrl),
        )
    server =
        buildServer(
                originRegex = ".*",
                port = 0,
                auth = NoOpAuthDecorator,
                counterStore = InMemoryCounterStore(),
                gitHubIssueStore = FakeGitHubIssueStore(),
                gitHubRepositoryStore = FakeGitHubRepositoryStore(),
                sessionStore = InMemorySessionStore(),
                workerAuthorizer = WorkerAuthorizer.permissive,
                issuePipelineStore = pipelines,
                githubOutboxStore = InMemoryGithubOutboxStore(backend),
                gitHubIssueClient = GitHubAppIssueClient(appClient),
                gitHubCandidateClient = GitHubAppCandidateClient(appClient),
                gitHubWebhookSecret = secret,
            )
            .also { it.start().join() }
  }

  @AfterTest
  fun tearDown() {
    server.stop().join()
    stub.close()
  }

  private val payload = """{"action":"labeled","repository":{"full_name":"$repo"}}"""

  @Test
  fun `a validly-signed delivery to the mounted route triggers a reconcile that picks the issue`() =
      runBlocking {
        stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready"))

        val status =
            stub.emitWebhook(
                targetUrl = "$serverUrl/webhook/github",
                event = "issues",
                payload = payload,
                secret = secret,
            )
        assertEquals(202, status)

        // The route fires a detached reconcile; wait (bounded) for the projected label.
        assertTrue(awaitLabel(), "issue should be picked + labeled after a signed delivery")
      }

  @Test
  fun `a bad signature is rejected and no reconcile runs`() = runBlocking {
    stub.seedIssue(repo, 1, "A", labels = setOf("flow:ready"))

    val status =
        stub.emitWebhook(
            targetUrl = "$serverUrl/webhook/github",
            event = "issues",
            payload = payload,
            secret = "wrong-secret",
        )
    assertEquals(401, status)

    delay(500)
    assertFalse("flow:in-progress" in stub.issue(repo, 1).labels, "no pick on a bad signature")
    assertTrue(pipelines.listLive().isEmpty())
  }

  private suspend fun awaitLabel(): Boolean {
    repeat(40) {
      if ("flow:in-progress" in stub.issue(repo, 1).labels) return true
      delay(100)
    }
    return "flow:in-progress" in stub.issue(repo, 1).labels
  }
}
