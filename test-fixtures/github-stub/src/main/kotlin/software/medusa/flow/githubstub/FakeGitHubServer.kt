package software.medusa.flow.githubstub

import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A local stand-in for the GitHub API, implementing exactly the endpoints Flow's ports use — REST
 * (App installation token, labels, comments, close, PRs) and the GraphQL queries (candidate search,
 * repo discovery, the merge gate). It returns only the fields Flow reads; **unknown routes 404
 * loudly**, so silent divergence from the real API surfaces as a failing test, not a false pass.
 *
 * State is seeded and mutated by the test through Kotlin control methods ([seedRepo], [seedIssue],
 * [mergePullRequest], [setCheckConclusion], [emitWebhook]) — the harness plays the human.
 */
class FakeGitHubServer : AutoCloseable {
  private val repos = ConcurrentHashMap<String, RepoState>()
  private val nextInstallationId = AtomicInteger(1000)
  private lateinit var server: Server

  /**
   * Base URL for a GitHub API client's base-URL override (e.g. `GitHubAppClient(webClient=...)`).
   */
  val baseUrl: String
    get() = "http://127.0.0.1:${server.activeLocalPort()}"

  fun start(): FakeGitHubServer {
    server =
        Server.builder()
            .http(0)
            .serviceUnder("/") { _, req ->
              HttpResponse.of(
                  req.aggregate().thenApply {
                    dispatch(it.method().name, it.path(), it.contentUtf8())
                  },
              )
            }
            .build()
    server.start().join()
    return this
  }

  override fun close() {
    if (::server.isInitialized) server.stop().join()
  }

  // ---- Control API (the test plays the human) -------------------------------------------------

  /** Registers a repo. [remotePath] (a [BareRepoFixture.remotePath]) backs clone/push, if given. */
  fun seedRepo(
      fullName: String,
      defaultBranch: String = "main",
      remotePath: String? = null,
  ): RepoState = repos.getOrPut(fullName) { RepoState(fullName, defaultBranch, remotePath) }

  /**
   * Adds an issue to a (auto-seeded) repo. [blockedBy] lists blocker issue numbers in the same
   * repo.
   */
  fun seedIssue(
      repoFullName: String,
      number: Int,
      title: String,
      body: String = "",
      labels: Set<String> = emptySet(),
      blockedBy: List<Int> = emptyList(),
      open: Boolean = true,
  ): Issue {
    val repo = seedRepo(repoFullName)
    val issue =
        Issue(
            number = number,
            title = title,
            body = body,
            url = "$baseUrl/$repoFullName/issues/$number",
            createdAt = Instant.now().minusSeconds((1000 - number).toLong()),
            labels = labels.toMutableSet(),
            blockedBy = blockedBy.toMutableList(),
            open = open,
        )
    repo.issues[number] = issue
    return issue
  }

  /**
   * Marks a PR merged with [mergeCommitSha]; the issue stays open (Flow closes it via reconcile).
   */
  fun mergePullRequest(
      repoFullName: String,
      prNumber: Int,
      mergeCommitSha: String,
  ) {
    val pr = repo(repoFullName).pulls[prNumber] ?: error("no PR #$prNumber in $repoFullName")
    pr.merged = true
    pr.open = false
    pr.mergeCommitSha = mergeCommitSha
  }

  /**
   * Sets a check conclusion on a commit (the merge gate reads these). Null conclusion = pending.
   */
  fun setCheckConclusion(
      repoFullName: String,
      commitSha: String,
      workflowName: String,
      conclusion: String?,
  ) {
    repo(repoFullName)
        .checksByCommit
        .getOrPut(commitSha) { mutableListOf() }
        .let { checks ->
          checks.removeAll { it.workflowName == workflowName }
          checks.add(CheckRun(workflowName, conclusion))
        }
  }

  /** The single open PR for [repoFullName], if any (convenience for the loop test's assertions). */
  fun openPullRequest(
      repoFullName: String,
  ): PullRequest? = repos[repoFullName]?.pulls?.values?.firstOrNull { it.open }

  /**
   * The open PR whose head branch is [head], if any. Lets a test target one engine's PR when
   * dual-engine fan-out has both engines opening a PR concurrently (so "the first open PR" is
   * racy).
   */
  fun pullRequestOnBranch(
      repoFullName: String,
      head: String,
  ): PullRequest? = repos[repoFullName]?.pulls?.values?.firstOrNull { it.open && it.head == head }

  /** Registers a PR with a fixed [number] (so a test can point a pipeline's pr_number at it). */
  fun seedPullRequest(
      repoFullName: String,
      number: Int,
      head: String = "flow/issue-$number",
  ): PullRequest {
    val repo = seedRepo(repoFullName)
    return PullRequest(number, head, repo.defaultBranch, title = "t", body = "b").also {
      repo.pulls[number] = it
    }
  }

  fun issue(
      repoFullName: String,
      number: Int,
  ): Issue = repo(repoFullName).issues[number] ?: error("no issue #$number in $repoFullName")

  /**
   * POSTs a `sha256`-HMAC-signed webhook (GitHub's `X-Hub-Signature-256` scheme) to [targetUrl].
   */
  fun emitWebhook(
      targetUrl: String,
      event: String,
      payload: String,
      secret: String,
  ): Int {
    val signature = "sha256=" + hmacSha256Hex(secret, payload)
    val request =
        JdkHttpRequest.newBuilder(URI.create(targetUrl))
            .header("Content-Type", "application/json")
            .header("X-GitHub-Event", event)
            .header("X-Hub-Signature-256", signature)
            .POST(JdkHttpRequest.BodyPublishers.ofString(payload))
            .build()
    return HttpClient.newHttpClient().send(request, BodyHandlers.ofString()).statusCode()
  }

  private fun repo(
      fullName: String,
  ): RepoState = repos[fullName] ?: error("repo not seeded: $fullName")

  // ---- Request dispatch -----------------------------------------------------------------------

  private fun dispatch(
      method: String,
      rawPath: String,
      body: String,
  ): HttpResponse {
    val path = rawPath.substringBefore('?')
    val query = rawPath.substringAfter('?', "")

    // App installation token dance (any bearer accepted — this is a fake).
    installationRegex.matchEntire(path)?.let {
      return json(
          HttpStatus.OK,
          buildJsonObject { put("id", nextInstallationId.getAndIncrement()) },
      )
    }
    if (method == "POST" && accessTokenRegex.matches(path)) {
      // Any bearer is accepted (this is a fake). expires_at is included because real GitHub always
      // sends it and the App-token minter parses it (to schedule refresh in the worker's case).
      return json(
          HttpStatus.CREATED,
          buildJsonObject {
            put("token", "fake-installation-token")
            put("expires_at", java.time.Instant.now().plusSeconds(3600).toString())
          },
      )
    }
    if (method == "GET" && path == "/installation/repositories") {
      return installationRepositories(query)
    }
    if (method == "POST" && path == "/graphql") return graphQl(body)

    labelsRegex.matchEntire(path)?.let { m -> if (method == "POST") return createLabel(m.repo) }
    issueLabelsRegex.matchEntire(path)?.let { m ->
      if (method == "POST") return addLabel(m.repo, m.number.toInt(), body)
    }
    issueLabelRegex.matchEntire(path)?.let { m ->
      if (method == "DELETE")
          return removeLabel(
              m.repo,
              m.number.toInt(),
              URLDecoder.decode(m.groupValues[4], Charsets.UTF_8),
          )
    }
    commentsRegex.matchEntire(path)?.let { m ->
      if (method == "POST") return postComment(m.repo, m.number.toInt(), body)
    }
    issueRegex.matchEntire(path)?.let { m ->
      if (method == "PATCH") return patchIssue(m.repo, m.number.toInt(), body)
      if (method == "GET") return getIssue(m.repo, m.number.toInt())
    }
    pullsRegex.matchEntire(path)?.let { m -> if (method == "POST") return createPull(m.repo, body) }
    pullRegex.matchEntire(path)?.let { m ->
      if (method == "GET") return getPull(m.repo, m.number.toInt())
    }

    return notFound("$method $rawPath — not implemented by FakeGitHubServer")
  }

  // ---- REST handlers --------------------------------------------------------------------------

  private fun installationRepositories(
      query: String,
  ): HttpResponse {
    val all = repos.values.map { it.fullName }.sorted()
    val nodes = buildJsonArray {
      all.forEach { fullName ->
        add(
            buildJsonObject {
              put("full_name", fullName)
              put("default_branch", repos.getValue(fullName).defaultBranch)
              put("html_url", "https://github.com/$fullName")
            },
        )
      }
    }
    return json(
        HttpStatus.OK,
        buildJsonObject {
          put("total_count", all.size)
          put("repositories", nodes)
        },
    )
  }

  private fun createLabel(
      repoFullName: String,
  ): HttpResponse {
    // Idempotent ensure: always report created; a real repo would 422 on a dup, which Flow also
    // treats as success — either is fine, so 201 keeps it simple.
    seedRepo(repoFullName)
    return json(HttpStatus.CREATED, buildJsonObject { put("id", 1) })
  }

  private fun addLabel(
      repoFullName: String,
      number: Int,
      body: String,
  ): HttpResponse {
    val labels = Json.parseToJsonElement(body).jsonObject["labels"]?.jsonArray.orEmpty()
    val issue =
        issueOrNull(repoFullName, number) ?: return notFound("no issue #$number in $repoFullName")
    labels.forEach { issue.labels.add(it.jsonPrimitive.content) }
    return json(HttpStatus.OK, buildJsonArray {})
  }

  private fun removeLabel(
      repoFullName: String,
      number: Int,
      label: String,
  ): HttpResponse {
    val issue =
        issueOrNull(repoFullName, number) ?: return notFound("no issue #$number in $repoFullName")
    return if (issue.labels.remove(label)) json(HttpStatus.OK, buildJsonArray {})
    else json(HttpStatus.NOT_FOUND, buildJsonObject { put("message", "Label does not exist") })
  }

  private fun postComment(
      repoFullName: String,
      number: Int,
      body: String,
  ): HttpResponse {
    val text = Json.parseToJsonElement(body).jsonObject["body"]?.jsonPrimitive?.content.orEmpty()
    val issue =
        issueOrNull(repoFullName, number) ?: return notFound("no issue #$number in $repoFullName")
    issue.comments.add(text)
    return json(HttpStatus.CREATED, buildJsonObject { put("id", issue.comments.size) })
  }

  private fun patchIssue(
      repoFullName: String,
      number: Int,
      body: String,
  ): HttpResponse {
    val issue =
        issueOrNull(repoFullName, number) ?: return notFound("no issue #$number in $repoFullName")
    val state = Json.parseToJsonElement(body).jsonObject["state"]?.jsonPrimitive?.content
    if (state == "closed") issue.open = false
    if (state == "open") issue.open = true
    return getIssue(repoFullName, number)
  }

  private fun getIssue(
      repoFullName: String,
      number: Int,
  ): HttpResponse {
    val issue =
        issueOrNull(repoFullName, number) ?: return notFound("no issue #$number in $repoFullName")
    return json(
        HttpStatus.OK,
        buildJsonObject {
          put("number", issue.number)
          put("title", issue.title)
          put("state", if (issue.open) "open" else "closed")
        },
    )
  }

  private fun createPull(
      repoFullName: String,
      body: String,
  ): HttpResponse {
    val repo = seedRepo(repoFullName)
    val obj = Json.parseToJsonElement(body).jsonObject
    val number = repo.nextPrNumber.getAndIncrement()
    val pr =
        PullRequest(
            number = number,
            head = obj["head"]?.jsonPrimitive?.content.orEmpty(),
            base = obj["base"]?.jsonPrimitive?.content ?: repo.defaultBranch,
            title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
            body = obj["body"]?.jsonPrimitive?.content.orEmpty(),
        )
    repo.pulls[number] = pr
    return json(
        HttpStatus.CREATED,
        buildJsonObject {
          put("number", number)
          put("html_url", "https://github.com/$repoFullName/pull/$number")
        },
    )
  }

  private fun getPull(
      repoFullName: String,
      number: Int,
  ): HttpResponse {
    val pr =
        repos[repoFullName]?.pulls?.get(number)
            ?: return notFound("no PR #$number in $repoFullName")
    return json(
        HttpStatus.OK,
        buildJsonObject {
          put("number", pr.number)
          put("state", if (pr.open) "open" else "closed")
          put("merged", pr.merged)
          pr.mergeCommitSha?.let { put("merge_commit_sha", it) }
        },
    )
  }

  // ---- GraphQL --------------------------------------------------------------------------------

  private fun graphQl(
      body: String,
  ): HttpResponse {
    val queryText =
        Json.parseToJsonElement(body).jsonObject["query"]?.jsonPrimitive?.content.orEmpty()
    return when {
      "statusCheckRollup" in queryText -> mergeGate(queryText)
      "repo:" in queryText -> candidateSearch(queryText)
      "org:" in queryText -> discoverySearch(queryText)
      else -> notFound("unrecognized GraphQL query:\n$queryText")
    }
  }

  private fun candidateSearch(
      queryText: String,
  ): HttpResponse {
    val repoFullName = Regex("""repo:(\S+)""").find(queryText)?.groupValues?.get(1)
    val label = Regex("""label:"([^"]+)"""").find(queryText)?.groupValues?.get(1) ?: "flow:ready"
    val repo = repoFullName?.let { repos[it] }
    val nodes = buildJsonArray {
      (repo?.issues?.values ?: emptyList())
          .filter { it.open && label in it.labels }
          .forEach { issue ->
            add(
                buildJsonObject {
                  put("number", issue.number)
                  put("title", issue.title)
                  put("body", issue.body)
                  put("url", issue.url)
                  put("createdAt", issue.createdAt.toString())
                  put(
                      "labels",
                      buildJsonObject {
                        put(
                            "nodes",
                            buildJsonArray {
                              issue.labels.forEach { labelName ->
                                add(buildJsonObject { put("name", labelName) })
                              }
                            },
                        )
                      },
                  )
                  put(
                      "blockedBy",
                      buildJsonObject {
                        put(
                            "nodes",
                            buildJsonArray {
                              issue.blockedBy.forEach { blockerNumber ->
                                val blocker = repo?.issues?.get(blockerNumber)
                                add(
                                    buildJsonObject {
                                      put("state", if (blocker?.open == false) "CLOSED" else "OPEN")
                                    },
                                )
                              }
                            },
                        )
                      },
                  )
                },
            )
          }
    }
    return searchEnvelope(nodes)
  }

  private fun discoverySearch(
      queryText: String,
  ): HttpResponse {
    val org = Regex("""org:(\S+)""").find(queryText)?.groupValues?.get(1)
    val label = Regex("""label:"([^"]+)"""").find(queryText)?.groupValues?.get(1) ?: "flow:ready"
    val nodes = buildJsonArray {
      repos.values
          .filter { org == null || it.fullName.substringBefore('/') == org }
          .filter { repo -> repo.issues.values.any { it.open && label in it.labels } }
          .forEach { repo ->
            add(
                buildJsonObject {
                  put("repository", buildJsonObject { put("nameWithOwner", repo.fullName) })
                },
            )
          }
    }
    return searchEnvelope(nodes)
  }

  private fun mergeGate(
      queryText: String,
  ): HttpResponse {
    val owner = Regex("""owner:\s*"([^"]+)"""").find(queryText)?.groupValues?.get(1)
    val name = Regex("""name:\s*"([^"]+)"""").find(queryText)?.groupValues?.get(1)
    val oid = Regex("""oid:\s*"([^"]+)"""").find(queryText)?.groupValues?.get(1)
    val checks = repos["$owner/$name"]?.checksByCommit?.get(oid).orEmpty()

    val commitObject: JsonObject? =
        if (checks.isEmpty()) {
          null // rollup absent ⇒ NoRuns
        } else {
          buildJsonObject {
            put("statusCheckRollup", buildJsonObject { put("state", rollupState(checks)) })
            put(
                "checkSuites",
                buildJsonObject {
                  put(
                      "nodes",
                      buildJsonArray {
                        checks.forEach { check ->
                          add(
                              buildJsonObject {
                                put("conclusion", check.conclusion)
                                put(
                                    "workflowRun",
                                    buildJsonObject {
                                      put(
                                          "workflow",
                                          buildJsonObject { put("name", check.workflowName) },
                                      )
                                    },
                                )
                              },
                          )
                        }
                      },
                  )
                },
            )
          }
        }

    return json(
        HttpStatus.OK,
        buildJsonObject {
          put(
              "data",
              buildJsonObject {
                put(
                    "repository",
                    buildJsonObject { put("object", commitObject ?: JsonObject(emptyMap())) },
                )
              },
          )
        },
    )
  }

  private fun rollupState(
      checks: List<CheckRun>,
  ): String =
      when {
        checks.any { it.conclusion == null } -> "PENDING"
        checks.all { it.conclusion == "SUCCESS" } -> "SUCCESS"
        else -> "FAILURE"
      }

  private fun searchEnvelope(
      nodes: JsonArray,
  ): HttpResponse =
      json(
          HttpStatus.OK,
          buildJsonObject {
            put(
                "data",
                buildJsonObject { put("search", buildJsonObject { put("nodes", nodes) }) },
            )
          },
      )

  private fun issueOrNull(
      repoFullName: String,
      number: Int,
  ): Issue? = repos[repoFullName]?.issues?.get(number)

  private fun json(
      status: HttpStatus,
      body: JsonObject,
  ): HttpResponse = HttpResponse.of(status, MediaType.JSON, body.toString())

  private fun json(
      status: HttpStatus,
      body: JsonArray,
  ): HttpResponse = HttpResponse.of(status, MediaType.JSON, body.toString())

  private fun notFound(
      message: String,
  ): HttpResponse =
      HttpResponse.of(
          HttpStatus.NOT_FOUND,
          MediaType.JSON,
          buildJsonObject { put("message", message) }.toString(),
      )

  // ---- State ----------------------------------------------------------------------------------

  class RepoState(
      val fullName: String,
      val defaultBranch: String,
      val remotePath: String?,
  ) {
    val issues = ConcurrentHashMap<Int, Issue>()
    val pulls = ConcurrentHashMap<Int, PullRequest>()
    val checksByCommit = ConcurrentHashMap<String, MutableList<CheckRun>>()
    val nextPrNumber = AtomicInteger(1)
  }

  class Issue(
      val number: Int,
      val title: String,
      val body: String,
      val url: String,
      val createdAt: Instant,
      val labels: MutableSet<String>,
      val blockedBy: MutableList<Int>,
      var open: Boolean,
  ) {
    val comments = mutableListOf<String>()
  }

  class PullRequest(
      val number: Int,
      val head: String,
      val base: String,
      val title: String,
      val body: String,
      var open: Boolean = true,
      var merged: Boolean = false,
      var mergeCommitSha: String? = null,
  )

  class CheckRun(
      val workflowName: String,
      val conclusion: String?,
  )

  private companion object {
    val installationRegex = Regex("""/repos/([^/]+)/([^/]+)/installation""")
    val accessTokenRegex = Regex("""/app/installations/\d+/access_tokens""")
    val labelsRegex = Regex("""/repos/([^/]+)/([^/]+)/labels""")
    val issueLabelsRegex = Regex("""/repos/([^/]+)/([^/]+)/issues/(\d+)/labels""")
    val issueLabelRegex = Regex("""/repos/([^/]+)/([^/]+)/issues/(\d+)/labels/(.+)""")
    val commentsRegex = Regex("""/repos/([^/]+)/([^/]+)/issues/(\d+)/comments""")
    val issueRegex = Regex("""/repos/([^/]+)/([^/]+)/issues/(\d+)""")
    val pullsRegex = Regex("""/repos/([^/]+)/([^/]+)/pulls""")
    val pullRegex = Regex("""/repos/([^/]+)/([^/]+)/pulls/(\d+)""")

    private fun hmacSha256Hex(
        secret: String,
        payload: String,
    ): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
      return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }
  }
}

/** `owner/repo` extracted from a two-capture path match. */
private val MatchResult.repo: String
  get() = "${groupValues[1]}/${groupValues[2]}"

private val MatchResult.number: String
  get() = groupValues[3]

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
