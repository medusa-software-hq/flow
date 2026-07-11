package software.medusa.flow.server

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

/**
 * Drives [GitHubWebhookService] through a real in-process Armeria server: correct HMAC fires a
 * repo-scoped reconcile and answers 202; bad/missing signatures are rejected 401; payloads without
 * a repository are accepted but fire nothing; and a burst for one repo is debounced to a single
 * fire.
 */
class GitHubWebhookService_tests {
  private companion object {
    const val secret = "s3cr3t-webhook-key"

    fun signatureFor(
        body: String,
    ): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
      val hex = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
      return "sha256=$hex"
    }
  }

  private lateinit var server: Server
  private val firedRepos = CopyOnWriteArrayList<String>()
  private var now = 1_000L

  @AfterTest
  fun tearDown() {
    if (::server.isInitialized) server.stop().join()
  }

  private fun startWith(
      service: GitHubWebhookService,
  ): WebClient {
    server =
        Server.builder().http(0).service(GitHubWebhookService.path, service).build().also {
          it.start().join()
        }
    return WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  private fun defaultService() =
      GitHubWebhookService(
          webhookSecret = secret,
          trigger = { repo -> firedRepos.add(repo) },
          nowMillis = { now },
      )

  private fun post(
      client: WebClient,
      body: String,
      signature: String?,
  ): HttpStatus {
    val builder =
        RequestHeaders.builder(HttpMethod.POST, GitHubWebhookService.path)
            .contentType(MediaType.JSON)
    if (signature != null) builder.add("X-Hub-Signature-256", signature)
    return client.execute(builder.build(), body).aggregate().join().status()
  }

  @Test
  fun `a correctly signed event fires a repo-scoped reconcile and answers 202`() {
    val client = startWith(defaultService())
    val body = """{"repository":{"full_name":"acme/app"}}"""

    val status = post(client, body, signatureFor(body))

    assertEquals(HttpStatus.ACCEPTED, status)
    assertEquals(listOf("acme/app"), firedRepos)
  }

  @Test
  fun `an invalid signature is rejected with 401 and fires nothing`() {
    val client = startWith(defaultService())
    val body = """{"repository":{"full_name":"acme/app"}}"""

    val status = post(client, body, "sha256=deadbeef")

    assertEquals(HttpStatus.UNAUTHORIZED, status)
    assertEquals(emptyList(), firedRepos)
  }

  @Test
  fun `a missing signature header is rejected with 401`() {
    val client = startWith(defaultService())
    val body = """{"repository":{"full_name":"acme/app"}}"""

    val status = post(client, body, signature = null)

    assertEquals(HttpStatus.UNAUTHORIZED, status)
    assertEquals(emptyList(), firedRepos)
  }

  @Test
  fun `a signature computed with the wrong secret is rejected`() {
    val client = startWith(defaultService())
    val body = """{"repository":{"full_name":"acme/app"}}"""
    val wrongMac = Mac.getInstance("HmacSHA256")
    wrongMac.init(SecretKeySpec("not-the-secret".toByteArray(), "HmacSHA256"))
    val wrongSig =
        "sha256=" + wrongMac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }

    val status = post(client, body, wrongSig)

    assertEquals(HttpStatus.UNAUTHORIZED, status)
    assertEquals(emptyList(), firedRepos)
  }

  @Test
  fun `a blank secret rejects even a locally-signed request`() {
    // With no configured secret the route can't verify anything, so it must fail closed.
    val service =
        GitHubWebhookService(webhookSecret = "", trigger = { repo -> firedRepos.add(repo) })
    val client = startWith(service)
    val body = """{"repository":{"full_name":"acme/app"}}"""

    val status = post(client, body, "sha256=00")

    assertEquals(HttpStatus.UNAUTHORIZED, status)
    assertEquals(emptyList(), firedRepos)
  }

  @Test
  fun `a signed event without a repository is accepted but fires nothing`() {
    val client = startWith(defaultService())
    val body = """{"zen":"Keep it logically awesome.","hook_id":1}"""

    val status = post(client, body, signatureFor(body))

    assertEquals(HttpStatus.ACCEPTED, status)
    assertEquals(emptyList(), firedRepos)
  }

  @Test
  fun `a burst for one repo within the window fires only once`() {
    val client = startWith(defaultService())
    val body = """{"repository":{"full_name":"acme/app"}}"""
    val sig = signatureFor(body)

    now = 1_000L
    assertEquals(HttpStatus.ACCEPTED, post(client, body, sig))
    now = 1_500L // within the 2s window
    assertEquals(HttpStatus.ACCEPTED, post(client, body, sig))
    now = 4_000L // past the window
    assertEquals(HttpStatus.ACCEPTED, post(client, body, sig))

    assertEquals(listOf("acme/app", "acme/app"), firedRepos)
  }

  @Test
  fun `debounce is per-repo`() {
    val client = startWith(defaultService())
    val bodyA = """{"repository":{"full_name":"acme/a"}}"""
    val bodyB = """{"repository":{"full_name":"acme/b"}}"""

    now = 1_000L
    post(client, bodyA, signatureFor(bodyA))
    now = 1_100L
    post(client, bodyB, signatureFor(bodyB))

    assertEquals(listOf("acme/a", "acme/b"), firedRepos)
  }
}
