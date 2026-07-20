package software.medusa.flow.server

import com.linecorp.armeria.client.WebClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.flow.githubstub.FakeGitHubAppKey
import software.medusa.flow.githubstub.FakeGitHubServer

/**
 * Story-01 acceptance: the *real* [GitHubAppIssueClient] (through the real [GitHubAppClient], App
 * JWT and installation-token dance included) drives a full label → comment → close cycle against
 * [FakeGitHubServer] via a base-URL override — the label/comment/close path that was previously
 * only verified by hand against the deployed API.
 */
class GitHubAppIssueClient_stubTests {
  private val stub = FakeGitHubServer().start()

  private val client =
      GitHubAppIssueClient(
          GitHubAppClient(
              GitHubAppConfig(
                  clientId = "fake-client-id",
                  pemContent = FakeGitHubAppKey.pkcs8Pem,
                  repoOwner = "acme",
                  repoName = "app",
              ),
              webClient = WebClient.of(stub.baseUrl),
          ),
      )

  @AfterTest fun tearDown() = stub.close()

  @Test
  fun `label, comment, and close flow drives the stub end to end`() = runBlocking {
    stub.seedIssue("acme/app", 7, "Do the thing", labels = setOf("flow:ready"))

    client.ensureLabelsExist("acme/app")
    client.addLabel("acme/app", 7, "flow:in-progress")
    client.postComment("acme/app", 7, "Working on it.")
    client.closeIssue("acme/app", 7)

    val issue = stub.issue("acme/app", 7)
    assertTrue("flow:in-progress" in issue.labels, "label was added")
    assertEquals(listOf("Working on it."), issue.comments)
    assertFalse(issue.open, "issue was closed")

    // removeLabel is idempotent (200 removed / 404 absent) — both must not throw.
    client.removeLabel("acme/app", 7, "flow:in-progress")
    client.removeLabel("acme/app", 7, "flow:in-progress") // already gone
    assertFalse("flow:in-progress" in issue.labels)
  }
}
