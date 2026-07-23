package software.medusa.flow.systemtest

import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.medusa.flow.v1.SessionState
import software.medusa.flow.v1.claimNextSessionRequest
import software.medusa.flow.v1.createSessionRequest
import software.medusa.flow.v1.failSessionRequest
import software.medusa.flow.v1.getSessionRequest
import software.medusa.flow.v1.heartbeatRequest
import software.medusa.flow.v1.listSessionsRequest
import software.medusa.flow.v1.reconcileRequest

/**
 * The smoke tier (story 02): fast, stateless post-deploy checks against a freshly deployed API +
 * SPA, so a deploy that is up but broken (bad env var, unreachable DB, wrong OAuth client) fails
 * here and blocks promotion. `@Smoke` so the promotion gate runs it as its own tier.
 *
 * Staging and prod always run a hosted worker (on-premises, managed via Workload), which
 * continuously claims sessions. The worker path is therefore smoked by asserting that worker is
 * *alive in the fleet* — not by racing it to claim a seeded session, which flaked whenever the real
 * worker won the claim. A worker outage now fails the gate with a clear "staging worker down"
 * diagnostic; that is intended — a promotion shouldn't proceed while the fleet is down.
 *
 * Each check that creates a session cleans up after itself (best-effort — the live worker may
 * terminate it first), so staging data stays legible.
 */
@Smoke
class SmokeTierTests : SystemTestBase() {
  private companion object {
    private const val smokeRepo = "smoke/test"
    private const val smokeTask = "# Smoke test session (safe to ignore)"
    private const val cleanupSummary = "post-deploy smoke; safe to ignore"
  }

  @Test
  fun `health endpoint returns 200`() {
    assertEquals(200, StagingHttp.getStatus("${config.apiUrl}/health")) {
      "GET ${config.apiUrl}/health should be 200"
    }
  }

  @Test
  fun `session round-trip (create, get, list)`() = runBlocking {
    val created =
        clients.sessionService.createSession(
            createSessionRequest {
              repoFullName = smokeRepo
              taskMarkdown = smokeTask
            },
        )
    val id = created.session.id
    assertTrue(id.isNotBlank()) { "CreateSession returned no id: $created" }

    try {
      val got = clients.sessionService.getSession(getSessionRequest { this.id = id })
      // Freshly created and PENDING — but a concurrent staging worker (M5's standing assumption)
      // may have already claimed it into RUNNING, which is equally healthy. A terminal state here
      // would be wrong.
      assertTrue(
          got.session.state == SessionState.SESSION_STATE_PENDING ||
              got.session.state == SessionState.SESSION_STATE_RUNNING,
      ) {
        "GetSession($id) state should be PENDING or RUNNING, was ${got.session.state}"
      }

      val listed = clients.sessionService.listSessions(listSessionsRequest {}).sessionsList
      assertTrue(listed.any { it.id == id }) { "ListSessions did not include $id" }
    } finally {
      drainClaimableSessions()
    }
  }

  @Test
  fun `a live worker is registered in the fleet`() = runBlocking {
    // Assert the hosted worker is alive via the fleet registry (the same liveness check the loop
    // tier preflights on) instead of racing it to claim a seeded session. requireLiveWorker throws
    // StagingWorkerDownException — a clear, actionable failure — if no worker has registered within
    // the freshness window, and logs the live worker's version/digest so skew against a
    // freshly promoted API is visible in the gate summary.
    val worker = WorkerLiveness.requireLiveWorker(clients, log = ::log)
    assertTrue(worker.workerId.isNotBlank()) { "live worker has a blank id: $worker" }
  }

  @Test
  fun `reconcile on an out-of-fence repo succeeds with no side effects`() = runBlocking {
    // A repo outside the reconciler's org fence: the call must succeed and do nothing.
    val response =
        clients.reconcileService.reconcile(
            reconcileRequest { repoFullName = "smoke/not-in-any-fenced-org" },
        )
    // Returning without a StatusException is the assertion (parity with smoke.sh check 4); the
    // explicit assert also keeps this method's return type Unit so JUnit discovers it as a @Test.
    assertNotNull(response)
  }

  @Test
  fun `webhook rejects a bad signature with 401`() {
    val code =
        StagingHttp.postStatus(
            url = "${config.apiUrl}/webhook/github",
            headers =
                mapOf(
                    "x-hub-signature-256" to "sha256=deadbeef",
                    "content-type" to "application/json",
                ),
            body = "{}",
        )
    assertEquals(401, code) { "POST /webhook/github with a bad signature should be 401, was $code" }
  }

  @Test
  fun `SPA is reachable`() {
    // IAP-gated, so an unauthenticated GET is redirected to the login screen (302), not the SPA
    // itself; any non-5xx proves the service is up and reachable.
    val code = StagingHttp.getStatus(config.webUrl)
    assertTrue(code in 200..499) { "SPA ${config.webUrl} returned $code (expected non-5xx)" }
  }

  /**
   * Best-effort cleanup for checks that create a session: claims → heartbeats → fails every
   * currently-claimable session (bounded at 20), acting as a worker client, and returns how many it
   * cycled. With a live worker present it usually drains nothing (the worker claimed first and will
   * terminate the session itself); either way staging holds only test/sandbox sessions, so failing
   * any we do claim is safe. No assertion depends on the count.
   */
  private suspend fun drainClaimableSessions(): Int {
    var drained = 0
    repeat(20) {
      val claim =
          try {
            clients.workerService.claimNextSession(claimNextSessionRequest {})
          } catch (e: StatusException) {
            log("ClaimNextSession failed (${e.status}); stopping drain")
            return drained
          }
      if (!claim.hasSession()) return drained
      clients.workerService.heartbeat(heartbeatRequest { sessionId = claim.session.id })
      clients.workerService.failSession(
          failSessionRequest {
            sessionId = claim.session.id
            failureSummary = cleanupSummary
          },
      )
      drained++
    }
    return drained
  }
}
