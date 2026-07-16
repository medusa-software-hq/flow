package software.medusa.flow.githubstub

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FakeGitHubServer_tests {
  private val stub = FakeGitHubServer().start()
  private val http = WebClient.of(stub.baseUrl)

  @AfterTest fun tearDown() = stub.close()

  private fun post(
      path: String,
      body: String,
  ) =
      http
          .execute(
              RequestHeaders.builder(HttpMethod.POST, path).contentType(MediaType.JSON).build(),
              body,
          )
          .aggregate()
          .join()

  private fun graphQl(
      query: String,
  ) =
      Json.parseToJsonElement(
          post("/graphql", """{"query":${Json.encodeToString(query)}}""").contentUtf8()
      )

  @Test
  fun `an unknown route 404s loudly`() {
    val response = http.get("/repos/acme/app/some-unimplemented-thing").aggregate().join()
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertTrue(response.contentUtf8().contains("not implemented"), response.contentUtf8())
  }

  @Test
  fun `candidate search returns only open flow-ready issues with blocked-by states`() {
    stub.seedIssue("acme/app", 1, "A", labels = setOf("flow:ready"))
    stub.seedIssue("acme/app", 2, "B", labels = setOf("flow:ready"), blockedBy = listOf(1))
    stub.seedIssue("acme/app", 3, "C — not ready", labels = emptySet())
    stub.seedIssue("acme/app", 4, "D — closed", labels = setOf("flow:ready"), open = false)

    val nodes =
        graphQl(
                """query { search(query: "repo:acme/app is:issue is:open label:"flow:ready"", type: ISSUE, first: 50) { nodes { ... on Issue { number blockedBy { nodes { state } } } } } }""",
            )
            .jsonObject["data"]!!
            .jsonObject["search"]!!
            .jsonObject["nodes"]!!
            .jsonArray

    val numbers = nodes.map { it.jsonObject["number"]!!.jsonPrimitive.content.toInt() }.toSet()
    assertEquals(setOf(1, 2), numbers) // 3 (no label) and 4 (closed) excluded

    val issueB = nodes.single { it.jsonObject["number"]!!.jsonPrimitive.content == "2" }
    val blockerState =
        issueB.jsonObject["blockedBy"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .single()
            .jsonObject["state"]!!
            .jsonPrimitive
            .content
    assertEquals("OPEN", blockerState) // blocker #1 is open

    // Close the blocker → its blocked-by edge flips to CLOSED.
    stub.issue("acme/app", 1).open = false
    val after =
        graphQl(
                """query { search(query: "repo:acme/app is:issue is:open label:"flow:ready"", type: ISSUE) { nodes { ... on Issue { number blockedBy { nodes { state } } } } } }""",
            )
            .jsonObject["data"]!!
            .jsonObject["search"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .single { it.jsonObject["number"]!!.jsonPrimitive.content == "2" }
    assertEquals(
        "CLOSED",
        after.jsonObject["blockedBy"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .single()
            .jsonObject["state"]!!
            .jsonPrimitive
            .content,
    )
  }

  @Test
  fun `discovery search is org-fenced and only counts open flow-ready repos`() {
    stub.seedIssue("acme/app", 1, "ready", labels = setOf("flow:ready"))
    stub.seedIssue("acme/other", 1, "ready", labels = setOf("flow:ready"))
    stub.seedIssue("stranger/x", 1, "ready", labels = setOf("flow:ready"))
    stub.seedIssue("acme/nolabel", 1, "no label")

    val repos =
        graphQl(
                """query { search(query: "org:acme is:issue is:open label:"flow:ready"", type: ISSUE) { nodes { ... on Issue { repository { nameWithOwner } } } } }""",
            )
            .jsonObject["data"]!!
            .jsonObject["search"]!!
            .jsonObject["nodes"]!!
            .jsonArray
            .map {
              it.jsonObject["repository"]!!.jsonObject["nameWithOwner"]!!.jsonPrimitive.content
            }
            .toSet()

    assertEquals(setOf("acme/app", "acme/other"), repos) // stranger fenced, nolabel excluded
  }

  @Test
  fun `PR create then merge transitions state and merge-gate reflects checks`() {
    val created =
        Json.parseToJsonElement(
            post(
                    "/repos/acme/app/pulls",
                    """{"title":"t","head":"flow/issue-1","base":"main","body":"b"}""",
                )
                .contentUtf8()
        )
    val number = created.jsonObject["number"]!!.jsonPrimitive.content.toInt()

    // Open PR: state open, not merged.
    val open =
        Json.parseToJsonElement(
            http.get("/repos/acme/app/pulls/$number").aggregate().join().contentUtf8()
        )
    assertEquals("open", open.jsonObject["state"]!!.jsonPrimitive.content)
    assertEquals("false", open.jsonObject["merged"]!!.jsonPrimitive.content)

    // Merge gate before any checks on a merge commit ⇒ empty object (NoRuns).
    stub.mergePullRequest("acme/app", number, mergeCommitSha = "deadbeef")
    val merged =
        Json.parseToJsonElement(
            http.get("/repos/acme/app/pulls/$number").aggregate().join().contentUtf8()
        )
    assertEquals("true", merged.jsonObject["merged"]!!.jsonPrimitive.content)
    assertEquals("deadbeef", merged.jsonObject["merge_commit_sha"]!!.jsonPrimitive.content)

    fun rollup(): String? =
        graphQl(
                """query { repository(owner: "acme", name: "app") { object(oid: "deadbeef") { ... on Commit { statusCheckRollup { state } checkSuites(first: 100) { nodes { conclusion workflowRun { workflow { name } } } } } } } }"""
            )
            .jsonObject["data"]!!
            .jsonObject["repository"]!!
            .jsonObject["object"]!!
            .jsonObject["statusCheckRollup"]
            ?.jsonObject
            ?.get("state")
            ?.jsonPrimitive
            ?.content

    assertEquals(null, rollup()) // no checks → rollup absent
    stub.setCheckConclusion("acme/app", "deadbeef", "Merge PR", conclusion = null)
    assertEquals("PENDING", rollup())
    stub.setCheckConclusion("acme/app", "deadbeef", "Merge PR", conclusion = "SUCCESS")
    assertEquals("SUCCESS", rollup())
  }

  @Test
  fun `emitWebhook signs the body with the sha256 HMAC scheme`() {
    val received = CopyOnWriteArrayList<Pair<String, String>>() // signature header, body
    val receiver =
        Server.builder()
            .http(0)
            .service("/webhook") { _, req ->
              com.linecorp.armeria.common.HttpResponse.of(
                  req.aggregate().thenApply {
                    received.add(
                        it.headers().get("X-Hub-Signature-256").orEmpty() to it.contentUtf8()
                    )
                    com.linecorp.armeria.common.HttpResponse.of(HttpStatus.OK)
                  },
              )
            }
            .build()
            .also { it.start().join() }

    try {
      val payload = """{"action":"labeled","repository":{"full_name":"acme/app"}}"""
      val status =
          stub.emitWebhook(
              targetUrl = "http://127.0.0.1:${receiver.activeLocalPort()}/webhook",
              event = "issues",
              payload = payload,
              secret = "s3cr3t",
          )
      assertEquals(200, status)

      val (signature, body) = received.single()
      assertEquals(payload, body)
      val expected = "sha256=" + hmacHex("s3cr3t", payload)
      assertEquals(expected, signature)
    } finally {
      receiver.stop().join()
    }
  }

  private fun hmacHex(
      secret: String,
      payload: String,
  ): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
