package software.medusa.flow.e2e

import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.flow.e2e.HermeticLoopHarness.Companion.issueNumber
import software.medusa.flow.e2e.HermeticLoopHarness.Companion.repoFullName
import software.medusa.flow.harness.HrsScriptedTaskCompleter
import software.medusa.flow.server.IssuePipeline
import software.medusa.flow.server.IssuePipelineState
import software.medusa.flow.server.Session
import software.medusa.flow.server.SessionState
import software.medusa.flow.test_utils.withMaterializedResource

/**
 * The failure flows a real model cannot produce on cue — the most manual part of every demo ("kill
 * the worker and watch"). Deterministic, no AI: they drive the story-03 harness with the *scripted*
 * engine, so they need no API key and can run anywhere.
 *
 * Each asserts both the internal transition (session/pipeline state) and the user-visible rendering
 * (`failureSummary`, events) — the thing the UI shows.
 */
class HermeticSadPaths_integrationTests {
  // Short enough that a lost worker's session expires in seconds; comfortably longer than the
  // worker's (also shortened) heartbeat interval, so a live worker is never mistaken for lost.
  private val heartbeatTimeout = Duration.ofSeconds(6)
  private val workerHeartbeatIntervalMillis = 500L
  private val branch = "flow/issue-$issueNumber"

  @Test
  fun `an empty diff fails the session with no branch pushed, and reconcile fails the pipeline`() =
      runSadPath("empty-diff") { harness, worker ->
        val session =
            harness.awaitSession("the session to fail") { it.state == SessionState.Failed }

        assertEquals("Engine produced no changes", session.failureSummary)
        assertFalse(
            harness.bareRepo.hasBranch(branch),
            "an empty diff must push no branch\n${worker.output()}",
        )

        harness.awaitPipelineFailed()
      }

  @Test
  fun `exhausted attempts render a structured failure the pipeline carries forward`() =
      runSadPath("attempts-exhausted") { harness, worker ->
        val session =
            harness.awaitSession("the session to fail") { it.state == SessionState.Failed }

        val summary = session.failureSummary.orEmpty()
        assertContains(summary, "Still unhealthy after 3 implementation attempt")
        assertContains(summary, HrsScriptedTaskCompleter.scriptedDiagnostic)

        // The structured failure reached the user-visible event stream, not just the summary field.
        val events = harness.sessions.get(session.id, afterSeq = 0)?.events.orEmpty()
        assertTrue(
            events.isNotEmpty(),
            "the session produced user-visible events\n${worker.output()}",
        )

        val pipeline = harness.awaitPipelineFailed()
        assertContains(
            pipeline.failureSummary.orEmpty(),
            HrsScriptedTaskCompleter.scriptedDiagnostic,
        )
      }

  @Test
  fun `a hung worker's heartbeats hold the session until it is killed, then it expires`() =
      runSadPath("hang") { harness, worker ->
        harness.awaitSession("the worker to claim") { it.state == SessionState.Running }

        // The engine is hanging, but the worker still heartbeats: after several intervals the
        // session is alive and the repo's mutex is held, exactly as during real long work.
        delay(2_500)
        harness.reconcile()
        assertEquals(
            SessionState.Running,
            harness.latestSession()?.state,
            "heartbeats must keep the hung session alive\n${worker.output()}",
        )
        assertTrue(
            harness.pipelines.listLive().any { it.state == IssuePipelineState.InProgress },
            "the repo mutex is held while the worker hangs",
        )

        // Kill it: no more heartbeats → lazy expiry → the session and pipeline fail cleanly.
        worker.kill()

        val expired =
            harness.awaitSession("the lost session to expire", timeoutMillis = 20_000) {
              it.state == SessionState.Failed
            }
        assertEquals("Worker lost", expired.failureSummary)

        harness.awaitPipelineFailed()
      }

  @Test
  fun `a crash after the push leaves a stray branch, and the next reconcile converges`() =
      runSadPath("crash-after-publish") { harness, worker ->
        // The engine patched, the worker pushed and opened a PR, then halted before
        // CompleteSession.
        harness.awaitTrue("the branch to be pushed") { harness.bareRepo.hasBranch(branch) }
        assertNotNull(
            harness.stub.openPullRequest(repoFullName),
            "the PR was opened before the crash\n${worker.output()}",
        )

        // The control plane never heard the work landed → lazy expiry → the pipeline converges to
        // FAILED rather than hanging forever on a worker that will never report back.
        harness.awaitSession("the crashed worker's session to expire", timeoutMillis = 20_000) {
          it.state == SessionState.Failed
        }
        harness.awaitPipelineFailed()

        // The stray branch is tolerated — nothing tried to force-clean it.
        assertTrue(harness.bareRepo.hasBranch(branch), "the stray branch is left in place")
      }

  // region Harness plumbing

  private fun runSadPath(
      behavior: String,
      block: suspend (HermeticLoopHarness, WorkerProcess) -> Unit,
  ) = runBlocking {
    withMaterializedResource(resourcePath = LoopFixture.gradle.resourcePath) { seedDirectory ->
      val workDirectory = Files.createTempDirectory("sad-path-")

      HermeticLoopHarness.start(
              fixture = LoopFixture.gradle,
              workDirectory = workDirectory,
              seedDirectory = seedDirectory,
              heartbeatTimeout = heartbeatTimeout,
          )
          .use { harness ->
            harness.stub.seedIssue(
                repoFullName,
                issueNumber,
                "Do the scripted thing",
                labels = setOf("flow:ready"),
            )

            harness
                .startScriptedWorker(
                    behavior = behavior,
                    heartbeatIntervalMillis = workerHeartbeatIntervalMillis,
                )
                .use { worker ->
                  // Pick the ready issue → create the session + pipeline the worker then claims.
                  harness.reconcile()
                  block(harness, worker)
                }
          }
    }
  }

  private suspend fun HermeticLoopHarness.latestSession(): Session? =
      sessions.list(limit = 10).firstOrNull()

  private suspend fun HermeticLoopHarness.awaitSession(
      what: String,
      timeoutMillis: Long = 15_000,
      predicate: (Session) -> Boolean,
  ): Session {
    val session =
        withTimeoutOrNull(timeoutMillis) {
          while (true) {
            // Reading the store also expires stale sessions, which is what drives lazy expiry here.
            sessions.list(limit = 10).firstOrNull()?.takeIf(predicate)?.let {
              return@withTimeoutOrNull it
            }
            delay(300)
          }
          @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    return assertNotNull(session, "timed out waiting for $what")
  }

  private suspend fun HermeticLoopHarness.awaitTrue(
      what: String,
      timeoutMillis: Long = 15_000,
      condition: () -> Boolean,
  ) {
    val reached =
        withTimeoutOrNull(timeoutMillis) {
          while (!condition()) delay(300)
          true
        }
    assertNotNull(reached, "timed out waiting for $what")
  }

  /** Reconciles until the issue's pipeline reaches FAILED (the converged terminal state). */
  private suspend fun HermeticLoopHarness.awaitPipelineFailed(
      timeoutMillis: Long = 15_000,
  ): IssuePipeline {
    val failed =
        withTimeoutOrNull(timeoutMillis) {
          while (true) {
            reconcile()
            pipelines
                .list(repoFullName)
                .firstOrNull {
                  it.issueNumber == issueNumber && it.state == IssuePipelineState.Failed
                }
                ?.let {
                  return@withTimeoutOrNull it
                }
            delay(300)
          }
          @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    return assertNotNull(failed, "timed out waiting for the pipeline to fail")
  }

  // endregion
}
