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
 * **Transient-flake retry (recorded):** the loop drives a real LLM, which fails
 * non-deterministically (a response cut short, `finish_reason=error`, a rate limit). Such a failure
 * is a flake, not a regression, and must not redden the promotion gate. A session that FAILS with a
 * *transient* signature ([LoopFailureClassifier]) is retried with a fresh session, up to
 * [maxAttempts] total; a *deterministic* failure (a real session failure, no PR, PR not open) fails
 * immediately, and a transient flake that doesn't clear on retry is treated as real. Transient
 * failures fail fast (the model errors early), so the retry doesn't blow the gate's time budget the
 * way retrying a genuine 15-minute run would — which is also why a *timeout* (session never
 * terminal) is **not** retried.
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

    // Total attempts — one retry on a transient model flake. A deterministic failure never retries.
    private const val maxAttempts = 2

    // The AI leg: clone + health gate + scout/plan/implement/verify + publish. Generous headroom;
    // the gate job's timeout must exceed this (and cover a fast transient failure + one full run).
    private val sessionCompleteTimeout: Duration = Duration.ofMinutes(15)
    private val pollInterval: Duration = Duration.ofSeconds(15)

    private val prNumberRegex = Regex("""/pull/(\d+)""")
  }

  /** A session failed with a transient (model/network) signature — worth one retry. */
  private class TransientLoopFailure(
      message: String,
  ) : Exception(message)

  private val sandboxRepo: String
    get() = System.getenv(sandboxRepoEnvVarName)?.takeIf { it.isNotBlank() } ?: defaultSandboxRepo

  @Test
  fun sessionIsClaimedByStagingWorkerAndCompletesToAPr() = runBlocking {
    // Preflight — distinct "staging worker down" if none live; prints version + skew. Once, not
    // per-attempt: worker liveness doesn't change between a flake and its retry.
    val worker = WorkerLiveness.requireLiveWorker(clients, ::log)
    log(
        "loop tier running against worker ${worker.workerId} (v=${worker.workerVersion.ifEmpty { "unknown" }})"
    )

    val github = SandboxGitHub.fromEnvironment(sandboxRepo)
    if (github == null) {
      log(
          "SANDBOX_GH_TOKEN not set — will assert via the session's prUrl only (no GitHub-side check/cleanup)"
      )
    }

    for (attempt in 1..maxAttempts) {
      try {
        runLoopOnce(attempt, github)
        return@runBlocking // success
      } catch (e: TransientLoopFailure) {
        if (attempt >= maxAttempts) {
          throw AssertionError(
              "loop tier failed after $maxAttempts attempts — the transient model flake did not " +
                  "clear on retry, so it is treated as a real failure: ${e.message}",
          )
        }
        log(
            "attempt $attempt/$maxAttempts hit a transient model flake; retrying with a fresh " +
                "session. Detail: ${e.message}"
        )
      }
      // Any non-transient failure (AssertionError, StagingWorkerDownException, …) propagates
      // immediately from runLoopOnce — no retry.
    }
  }

  /** One full attempt: create a session → await a terminal state → assert the PR → clean up. */
  private suspend fun runLoopOnce(
      attempt: Int,
      github: SandboxGitHub?,
  ) {
    val runId = "m5-loop-${System.currentTimeMillis()}-a$attempt"
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
    log("attempt $attempt: created session $sessionId (marker $runId) on $sandboxRepo")

    var prNumber: Int? = null
    try {
      // Await a terminal state. A FAILED session is classified transient (retry) vs real (fail
      // now);
      // PENDING/RUNNING keeps polling until the timeout (a timeout is never retried).
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
                  throw classifyFailure(sessionId, session.failureSummary)
              else -> null // PENDING (not yet claimed) / RUNNING — keep waiting
            }
          }

      // Assert the PR.
      assertTrue(completed.prUrl.isNotBlank()) {
        "session completed but carries no prUrl: $completed"
      }
      val parsedPrNumber =
          prNumberRegex.find(completed.prUrl)?.groupValues?.get(1)?.toIntOrNull()
              ?: throw AssertionError("could not parse a PR number from prUrl=${completed.prUrl}")
      prNumber = parsedPrNumber
      log("session completed with PR ${completed.prUrl} (#$parsedPrNumber)")

      if (github != null) {
        val pr = github.getPullRequest(parsedPrNumber)
        assertEquals("open", pr.state) { "PR #$parsedPrNumber should be open, was ${pr.state}" }
        log("verified PR #$parsedPrNumber is open on $sandboxRepo (branch ${pr.headRef})")
      }
    } finally {
      // Cleanup — close the PR + delete its branch so staging stays legible. Best-effort. A
      // transient failure fails before a PR exists, so this no-ops on those attempts.
      val toClean = prNumber
      if (github != null && toClean != null) {
        val headRef = runCatching { github.getPullRequest(toClean).headRef }.getOrNull()
        github.closePullRequestAndDeleteBranch(toClean, headRef)
        log("cleaned up PR #$toClean")
      }
    }
  }

  /**
   * Classifies a FAILED session: a [TransientLoopFailure] (a model/network flake, worth a retry) or
   * an [AssertionError] (a real regression, fail the gate now).
   */
  private fun classifyFailure(
      sessionId: String,
      failureSummary: String,
  ): Throwable =
      if (LoopFailureClassifier.isTransient(failureSummary)) {
        TransientLoopFailure(
            "session $sessionId FAILED (transient model/network flake):\n$failureSummary"
        )
      } else {
        AssertionError("session $sessionId FAILED:\n$failureSummary")
      }
}
