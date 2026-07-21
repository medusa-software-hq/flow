package software.medusa.flow.systemtest

import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.medusa.flow.v1.SessionState
import software.medusa.flow.v1.createSessionRequest
import software.medusa.flow.v1.getSessionRequest

/**
 * The loop tier (story 03) — the M5 headline: every promotion proves the full real loop before prod
 * deploys. A session created against staging is claimed by staging's own admin-run worker and
 * completes to a PR on the sandbox org.
 *
 * Guarded by the story-01 worker-liveness preflight, so a worker outage reads as a distinct
 * **"staging worker down"** ([StagingWorkerDownException]) rather than a generic timeout, and the
 * smoke tier stays green independently.
 *
 * **Scope decision (recorded):** the *direct* session→PR loop, not the full M2 reconcile cycle
 * (reconcile → merge → close → dependent). Per-promotion, the nightly-grade full-cycle assertions
 * are overkill; the direct loop proves worker liveness + clone + engine + publish end to end. The
 * M2-cycle variant stays a nightly/ad-hoc concern (the retired nightly's rationale).
 *
 * **Engine decision (recorded):** the session requests `ENGINE_UNSPECIFIED`, so the claiming
 * worker's default runs it — `builtin` on staging (the `flow-worker-staging` profile). The loop
 * verifies the *pipeline*, not engine output quality, and builtin is the cheaper, always-available
 * default; `claude` would add the OAuth-token dependency and cost for no extra pipeline coverage.
 *
 * **Budget (recorded):** the task is deliberately trivial (a one-line Javadoc), and
 * [sessionCompleteTimeout] bounds wall-clock; the worker's OpenRouter key budget cap is the hard
 * spend ceiling (as the retired nightly noted). A unique per-run marker keeps concurrent runs from
 * colliding; the gate additionally serializes promotions with a concurrency group.
 */
@Loop
class LoopTierTest : SystemTestBase() {
  private companion object {
    private const val sandboxRepoEnvVarName = "FLOW_SANDBOX_REPO"
    private const val defaultSandboxRepo = "medusa-software-test-hq/flow-sandbox-fixture"

    // The AI leg: clone + health gate + scout/plan/implement/verify + publish. Generous headroom;
    // the gate job's timeout must exceed this.
    private val sessionCompleteTimeout: Duration = Duration.ofMinutes(15)
    private val pollInterval: Duration = Duration.ofSeconds(15)

    private val prNumberRegex = Regex("""/pull/(\d+)""")
  }

  private val sandboxRepo: String
    get() = System.getenv(sandboxRepoEnvVarName)?.takeIf { it.isNotBlank() } ?: defaultSandboxRepo

  @Test
  fun sessionIsClaimedByStagingWorkerAndCompletesToAPr() = runBlocking {
    // 1. Preflight — distinct "staging worker down" if none live; prints version + skew.
    val worker = WorkerLiveness.requireLiveWorker(clients, ::log)
    log(
        "loop tier running against worker ${worker.workerId} (v=${worker.workerVersion.ifEmpty { "unknown" }})"
    )

    // 2. Create a uniquely-marked session on the sandbox fixture.
    val runId = "m5-loop-${System.currentTimeMillis()}"
    val created =
        clients.sessionService.createSession(
            createSessionRequest {
              repoFullName = sandboxRepo
              taskMarkdown =
                  "# Add a class Javadoc comment\n\n" +
                      "Add a short one-line Javadoc summary to the `Greeter` class in " +
                      "Greeter.java. Do not change behavior.\n\nRun marker: $runId"
              // engine left UNSPECIFIED → the staging worker's default (builtin).
            },
        )
    val sessionId = created.session.id
    log("created session $sessionId (marker $runId) on $sandboxRepo")

    val github = SandboxGitHub.fromEnvironment(sandboxRepo)
    if (github == null) {
      log(
          "SANDBOX_GH_TOKEN not set — will assert via the session's prUrl only (no GitHub-side check/cleanup)"
      )
    }

    var prNumber: Int? = null
    try {
      // 3. Await a terminal state. FAILED is a definitive negative (surface its summary at once);
      //    still PENDING/RUNNING → keep polling until the timeout.
      val completed =
          awaitUntil(
              description = "session $sessionId to complete",
              timeout = sessionCompleteTimeout,
              pollInterval = pollInterval,
              onPoll = ::log,
          ) {
            val session =
                clients.sessionService.getSession(getSessionRequest { id = sessionId }).session
            when (session.state) {
              SessionState.SESSION_STATE_COMPLETED -> session
              SessionState.SESSION_STATE_FAILED ->
                  throw AssertionError(
                      "session $sessionId FAILED:\n${session.failureSummary}",
                  )
              else -> null // PENDING (not yet claimed) / RUNNING — keep waiting
            }
          }

      // 4. Assert the PR.
      assertTrue(completed.prUrl.isNotBlank()) {
        "session completed but carries no prUrl: $completed"
      }
      prNumber =
          prNumberRegex.find(completed.prUrl)?.groupValues?.get(1)?.toIntOrNull()
              ?: throw AssertionError("could not parse a PR number from prUrl=${completed.prUrl}")
      log("session completed with PR ${completed.prUrl} (#$prNumber)")

      if (github != null) {
        val pr = github.getPullRequest(prNumber!!)
        assertEquals("open", pr.state) { "PR #$prNumber should be open, was ${pr.state}" }
        log("verified PR #$prNumber is open on $sandboxRepo (branch ${pr.headRef})")
      }
    } finally {
      // 5. Cleanup — close the PR + delete its branch so staging stays legible. Best-effort.
      if (github != null && prNumber != null) {
        val headRef = runCatching { github.getPullRequest(prNumber!!).headRef }.getOrNull()
        github.closePullRequestAndDeleteBranch(prNumber!!, headRef)
        log("cleaned up PR #$prNumber")
      }
    }
  }
}
