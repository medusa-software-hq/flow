package software.medusa.flow.e2e

import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.flow.e2e.HermeticLoopHarness.Companion.issueNumber
import software.medusa.flow.e2e.HermeticLoopHarness.Companion.repoFullName
import software.medusa.flow.test_utils.withMaterializedResource

/**
 * The full-loop scenario — the M1 demo (and the core M2 cycle) as an automated test — factored out
 * of the test class so it can be driven by any engine. The only thing that varies per engine is how
 * the worker subprocess is started ([startWorker]) and, for CI accounting, the flake label
 * ([labelSuffix]); the fixture, the assertions, and the one-retry flake budget are shared verbatim.
 *
 * ## Flake budget
 *
 * A real model can always have a bad day, so the AI leg gets **one** automatic retry. Both the
 * outcome and whether the retry was needed are printed as a single machine-readable line
 * (`LOOP_RESULT ...`) so the flake rate is measurable from CI history rather than guessed at.
 *
 * If retries are needed in more than ~2% of runs, that is a signal to fix — not to raise the retry
 * budget: escalate by making the fixture task more trivial, or by pinning a steadier model. A test
 * that passes only because it retries is a test that has stopped measuring anything.
 */
class HermeticLoopScenario(
    /** Starts the worker subprocess under test — the engine-specific seam (builtin vs claude). */
    private val startWorker: (HermeticLoopHarness) -> WorkerProcess,
    /**
     * A model call per round plus nested Gradle builds for the fixture's checks. 8 minutes per
     * attempt, and it may retry once.
     */
    private val loopTimeoutMillis: Long = 8 * 60 * 1000L,
    /**
     * Appended to the reported fixture name in the `LOOP_RESULT` line (e.g. `-claude`) so each
     * engine's CI flake history is separable. Empty for the builtin engine.
     */
    private val labelSuffix: String = "",
) {
  fun runWithOneRetry(
      fixture: LoopFixture,
  ) {
    val firstAttempt = runCatching { runLoop(fixture, attempt = 1) }

    if (firstAttempt.isSuccess) {
      reportLoopResult(fixture, outcome = "pass", retried = false)
      return
    }

    System.err.println(
        "[loop] attempt 1 failed for ${fixture.name}$labelSuffix; retrying once (flake budget).\n" +
            firstAttempt.exceptionOrNull()?.stackTraceToString(),
    )

    val secondAttempt = runCatching { runLoop(fixture, attempt = 2) }

    if (secondAttempt.isSuccess) {
      reportLoopResult(fixture, outcome = "pass", retried = true)
      return
    }

    reportLoopResult(fixture, outcome = "fail", retried = true)

    // Surface the *second* failure, with the first attached: whatever is wrong is likelier to be
    // visible in both than in either alone.
    throw AssertionError(
        "the loop failed twice for ${fixture.name}$labelSuffix. First attempt:\n" +
            firstAttempt.exceptionOrNull()?.stackTraceToString(),
        secondAttempt.exceptionOrNull(),
    )
  }

  /** One machine-readable line per run, so CI history answers "how often does this flake?". */
  private fun reportLoopResult(
      fixture: LoopFixture,
      outcome: String,
      retried: Boolean,
  ) {
    println("LOOP_RESULT fixture=${fixture.name}$labelSuffix outcome=$outcome retried=$retried")
  }

  private fun runLoop(
      fixture: LoopFixture,
      attempt: Int,
  ) = runBlocking {
    withMaterializedResource(resourcePath = fixture.resourcePath) { seedDirectory ->
      val workDirectory = Files.createTempDirectory("hermetic-loop-")

      HermeticLoopHarness.start(
              fixture = fixture,
              workDirectory = workDirectory,
              seedDirectory = seedDirectory,
          )
          .use { harness ->
            println("[loop] attempt $attempt: api=${harness.apiUrl} stub=${harness.stub.baseUrl}")

            // A ready issue, and a second one blocked on it — the dependency the loop must unblock.
            harness.stub.seedIssue(
                repoFullName,
                issueNumber,
                fixture.issueTitle,
                body = fixture.issueBody,
                labels = setOf("flow:ready"),
            )
            harness.stub.seedIssue(
                repoFullName,
                2,
                "Blocked follow-up",
                labels = setOf("flow:ready"),
                blockedBy = listOf(issueNumber),
            )

            startWorker(harness).use { worker ->
              // 1. The scheduler picks the unblocked issue and creates a session to claim.
              harness.reconcile()
              awaitOrFail(worker, "a session to be created") {
                harness.sessions.list(limit = 10).isNotEmpty()
              }

              // 2. The worker claims it, and the engine does the work: clone (real git, redirected
              //    at the bare repo), edit, run the fixture's checks, push, open a PR in the stub.
              // The pipeline is driven by the primary (Claude) session; both engines now publish in
              // parallel, so target the primary's engine-scoped branch specifically rather than
              // "the
              // first open PR" (which could be the built-in shadow's).
              val branch = "flow/issue-$issueNumber-claude"

              val startedAt = System.currentTimeMillis()
              awaitOrFail(
                  worker,
                  "the primary engine to open its PR",
                  timeoutMillis = loopTimeoutMillis,
              ) {
                harness.stub.pullRequestOnBranch(repoFullName, branch) != null
              }
              println(
                  "[loop] primary PR opened after ${(System.currentTimeMillis() - startedAt) / 1000}s",
              )

              // 3. The branch really landed on the real remote...
              assertTrue(
                  harness.bareRepo.hasBranch(branch),
                  "the worker must push $branch to the remote\n${worker.output()}",
              )

              // ...and it really contains the change the task asked for.
              assertContains(
                  harness.bareRepo.showFile(fixture.provingFilePath, branch),
                  fixture.provingContent,
                  message = "the pushed branch must contain the model's edit\n${worker.output()}",
              )

              // 4. The PR references the issue without closing it — closing is the reconciler's
              // job.
              val pr = checkNotNull(harness.stub.pullRequestOnBranch(repoFullName, branch))
              assertContains(pr.body, "Refs #$issueNumber")

              // 5. Merge it green, exactly as GitHub would.
              val mergeSha = harness.bareRepo.tipSha(branch)
              harness.stub.mergePullRequest(repoFullName, pr.number, mergeCommitSha = mergeSha)
              harness.stub.setCheckConclusion(
                  repoFullName,
                  mergeSha,
                  "Merge PR",
                  conclusion = "SUCCESS",
              )

              // 6. The reconciler observes the merged, green PR and closes the issue in GitHub.
              harness.reconcileUntil("issue #$issueNumber closed") {
                !harness.stub.issue(repoFullName, issueNumber).open
              }

              // 7. With its blocker closed, the dependent becomes pickable.
              harness.reconcileUntil("issue #2 picked") {
                "flow:in-progress" in harness.stub.issue(repoFullName, 2).labels
              }

              assertTrue(worker.isAlive, "the worker died mid-loop:\n${worker.output()}")
            }
          }
    }
  }

  /**
   * Polls until [condition], failing with the worker's own output rather than a bare timeout — this
   * harness is the first thing an engine change breaks, and it has to say why.
   */
  private suspend fun awaitOrFail(
      worker: WorkerProcess,
      what: String,
      timeoutMillis: Long = 60_000,
      condition: suspend () -> Boolean,
  ) {
    val reached =
        withTimeoutOrNull(timeoutMillis) {
          while (!condition()) {
            worker.exitCodeOrNull()?.let { exitCode ->
              throw AssertionError(
                  "the worker exited with $exitCode while waiting for $what:\n${worker.output()}",
              )
            }
            delay(500)
          }
          true
        }

    assertFalse(
        reached == null,
        "timed out after ${timeoutMillis / 1000}s waiting for $what. Worker output:\n${worker.output()}",
    )
  }
}
