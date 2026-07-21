package software.medusa.flow.systemtest

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal GitHub REST client for the loop tier's PR assertion + cleanup on the sandbox org. Built
 * only when a token is configured ([fromEnvironment] returns null otherwise) — the sandbox App
 * installation token the gate mints (`SANDBOX_GH_TOKEN`). Without it the loop tier still asserts
 * the PR via the session's `prUrl`, but can't do the GitHub-side check or clean up.
 *
 * Deliberately dependency-free (regex field extraction, no JSON lib) — the module is a thin client
 * and the fields it reads are flat scalars.
 */
class SandboxGitHub
internal constructor(
    private val repoFullName: String,
    private val token: String,
) {
  private val http: HttpClient =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  data class PullRequest(
      val number: Int,
      val state: String,
      val headRef: String,
  )

  fun getPullRequest(
      number: Int,
  ): PullRequest {
    val resp = request("GET", "/repos/$repoFullName/pulls/$number")
    check(resp.statusCode() == 200) {
      "GET pull #$number returned ${resp.statusCode()}: ${resp.body().take(300)}"
    }
    return PullRequest(
        number = number,
        state = extractString(resp.body(), "state"),
        headRef = extractHeadRef(resp.body()),
    )
  }

  /** Best-effort cleanup: close the PR and delete its branch. Never throws. */
  fun closePullRequestAndDeleteBranch(
      number: Int,
      headRef: String?,
  ) {
    runCatching { request("PATCH", "/repos/$repoFullName/pulls/$number", """{"state":"closed"}""") }
    headRef
        ?.takeIf { it.isNotBlank() }
        ?.let { ref ->
          runCatching { request("DELETE", "/repos/$repoFullName/git/refs/heads/$ref") }
        }
  }

  private fun request(
      method: String,
      path: String,
      body: String? = null,
  ): HttpResponse<String> {
    val builder =
        HttpRequest.newBuilder(URI.create("https://api.github.com$path"))
            .timeout(Duration.ofSeconds(30))
            .header("authorization", "Bearer $token")
            .header("accept", "application/vnd.github+json")
            .header("x-github-api-version", "2022-11-28")
    when (method) {
      "GET" -> builder.GET()
      "PATCH" -> builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body ?: "{}"))
      "DELETE" -> builder.DELETE()
      else -> error("unsupported method $method")
    }
    return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
  }

  companion object {
    fun fromEnvironment(
        repoFullName: String,
        lookup: (String) -> String? = System::getenv,
    ): SandboxGitHub? {
      val token =
          (lookup("SANDBOX_GH_TOKEN") ?: lookup("SANDBOX_TOKEN"))?.takeIf { it.isNotBlank() }
              ?: return null
      return SandboxGitHub(repoFullName, token)
    }

    private fun extractString(
        json: String,
        field: String,
    ): String = Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1).orEmpty()

    /** `"head": { ... "ref": "<branch>" ... }` — the first `ref` after the `head` key. */
    private fun extractHeadRef(
        json: String,
    ): String {
      val headIdx = json.indexOf("\"head\"")
      if (headIdx < 0) return ""
      return Regex("\"ref\"\\s*:\\s*\"([^\"]*)\"")
          .find(json, headIdx)
          ?.groupValues
          ?.get(1)
          .orEmpty()
    }
  }
}
