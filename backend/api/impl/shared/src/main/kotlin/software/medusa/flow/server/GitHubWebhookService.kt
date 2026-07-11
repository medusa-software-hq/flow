package software.medusa.flow.server

import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** Fire-and-forget repo reconcile, so the webhook can respond 202 without waiting for the pass. */
fun interface WebhookReconcileTrigger {
  fun fire(repoFullName: String)
}

/**
 * The GitHub webhook endpoint — a *latency accelerant*, never trusted for correctness: the system
 * behaves identically (only slower) with this route removed, because the scheduler and the SQL
 * uniqueness backstops still drive every transition.
 *
 * Each request is HMAC-verified (`X-Hub-Signature-256` over the raw body, keyed on the shared
 * webhook secret); anything unverifiable is `401`ed and logged. A verified request contributes only
 * its `repository.full_name` — the payload is otherwise ignored — and fires a repo-scoped reconcile
 * asynchronously, answering `202` immediately. A short per-repo debounce absorbs the burst of
 * events GitHub emits around a single merge.
 */
class GitHubWebhookService(
    private val webhookSecret: String,
    private val trigger: WebhookReconcileTrigger,
    private val debounceWindowMillis: Long = defaultDebounceWindowMillis,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : HttpService {
  companion object {
    const val path = "/webhook/github"

    // Absorbs the burst of events (pull_request, workflow_run, check_suite, ...) GitHub fires
    // around
    // a merge into a single reconcile; the scheduler backstop covers anything this coalesces away.
    const val defaultDebounceWindowMillis = 2_000L

    private const val signatureHeaderName = "X-Hub-Signature-256"
    private const val signaturePrefix = "sha256="
    private const val hmacAlgorithm = "HmacSHA256"

    private val json = Json { ignoreUnknownKeys = true }
    private val log = LoggerFactory.getLogger(GitHubWebhookService::class.java)
  }

  // Last time a reconcile was *fired* per repo (not merely received) — the debounce reference.
  private val lastFiredMillisByRepo = ConcurrentHashMap<String, Long>()

  override fun serve(
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse = HttpResponse.of(req.aggregate().thenApply { handle(it) })

  private fun handle(
      request: AggregatedHttpRequest,
  ): HttpResponse {
    val signature = request.headers().get(signatureHeaderName)
    val body = request.content().array()

    if (!signatureIsValid(body = body, providedSignature = signature)) {
      log.warn("rejected GitHub webhook: missing or invalid {}", signatureHeaderName)
      return HttpResponse.of(HttpStatus.UNAUTHORIZED)
    }

    val repoFullName = parseRepoFullName(request.contentUtf8())
    if (repoFullName == null) {
      // A ping, or an event with no repository (org-level) — nothing repo-scoped to reconcile.
      return HttpResponse.of(HttpStatus.ACCEPTED)
    }

    if (shouldFire(repoFullName)) {
      log.info("GitHub webhook: firing reconcile for {}", repoFullName)
      trigger.fire(repoFullName)
    } else {
      log.info("GitHub webhook: debounced reconcile for {}", repoFullName)
    }

    return HttpResponse.of(HttpStatus.ACCEPTED)
  }

  private fun signatureIsValid(
      body: ByteArray,
      providedSignature: String?,
  ): Boolean {
    // A blank secret can't verify anything — fail closed rather than accept unverified events.
    if (webhookSecret.isEmpty() || providedSignature == null) return false

    val expected = signaturePrefix + hmacSha256Hex(body)
    // Constant-time compare — never leak how much of the signature matched.
    return MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8),
        providedSignature.toByteArray(Charsets.UTF_8),
    )
  }

  private fun hmacSha256Hex(
      body: ByteArray,
  ): String {
    val mac = Mac.getInstance(hmacAlgorithm)
    mac.init(SecretKeySpec(webhookSecret.toByteArray(Charsets.UTF_8), hmacAlgorithm))
    return mac.doFinal(body).joinToString("") { "%02x".format(it) }
  }

  private fun parseRepoFullName(
      body: String,
  ): String? =
      runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject["repository"]
                ?.jsonObject
                ?.get("full_name")
                ?.jsonPrimitive
                ?.content
          }
          .getOrNull()
          ?.takeIf { it.isNotEmpty() }

  /**
   * Atomically decides whether to fire for [repoFullName] and, when firing, records the timestamp.
   * A burst within [debounceWindowMillis] collapses onto the first fire; the window is measured
   * from the last *fire*, so a steady stream still fires once per window rather than starving.
   */
  private fun shouldFire(
      repoFullName: String,
  ): Boolean {
    val now = nowMillis()
    var fire = false
    lastFiredMillisByRepo.compute(repoFullName) { _, previous ->
      if (previous == null || now - previous >= debounceWindowMillis) {
        fire = true
        now
      } else {
        previous
      }
    }
    return fire
  }
}
