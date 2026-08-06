package software.medusa.flow.server

import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.flow.v1.IssuePipelineState
import software.medusa.flow.v1.ListIssuePipelinesRequest
import software.medusa.flow.v1.clearIssuePipelineRequest
import software.medusa.flow.v1.listIssuePipelinesRequest
import software.medusa.flow.v1.watchIssuePipelinesRequest

class PipelineServiceImpl_tests {
  private fun newFixture(watchPollInterval: Duration = 20.milliseconds): Fixture {
    val backend = InMemoryPipelineBackend()
    val pipelines = InMemoryIssuePipelineStore(backend)
    val outbox = InMemoryGithubOutboxStore(backend)
    val sessions = InMemorySessionStore()
    return Fixture(
        pipelines,
        outbox,
        sessions,
        PipelineServiceImpl(pipelines, outbox, sessions, watchPollInterval = watchPollInterval),
    )
  }

  private data class Fixture(
      val pipelines: InMemoryIssuePipelineStore,
      val outbox: InMemoryGithubOutboxStore,
      val sessions: InMemorySessionStore,
      val service: PipelineServiceImpl,
  )

  private suspend fun InMemoryIssuePipelineStore.pickA(
      repo: String = "acme/app",
      issue: Int = 1,
  ): IssuePipeline {
    val result = pick(repo, issue, "Issue $issue", "https://x/$issue", SessionId("s$issue"))
    return assertIs<PickResult.Picked>(result).pipeline
  }

  @Test
  fun `listIssuePipelines maps rows and filters by repo`() = runBlocking {
    val f = newFixture()
    f.pipelines.pickA(repo = "acme/app", issue = 1)
    f.pipelines.pickA(repo = "other/repo", issue = 2)

    val all = f.service.listIssuePipelines(ListIssuePipelinesRequest.getDefaultInstance())
    assertEquals(2, all.pipelinesCount)

    val filtered =
        f.service.listIssuePipelines(listIssuePipelinesRequest { repoFullName = "acme/app" })
    assertEquals(1, filtered.pipelinesCount)
    val row = filtered.pipelinesList.single()
    assertEquals("acme/app", row.repoFullName)
    assertEquals(1, row.issueNumber)
    assertEquals("Issue 1", row.issueTitle)
    assertEquals("s1", row.sessionId)
    assertFalse(row.cleared)
    assertFalse(row.outboxStuck)
  }

  @Test
  fun `clearIssuePipeline clears a FAILED pipeline`() = runBlocking {
    val f = newFixture()
    val pipeline = f.pipelines.pickA()
    f.pipelines.markFailed(pipeline.id, "boom")

    val response = f.service.clearIssuePipeline(clearIssuePipelineRequest { id = pipeline.id.id })

    assertTrue(response.pipeline.cleared)
    // The row is now dead -> the repo is free for a re-pick.
    assertFalse(f.pipelines.isRepoBusy("acme/app"))
  }

  @Test
  fun `clearing a pipeline abandons its still-running session`() = runBlocking {
    val f = newFixture()
    // A session claimed by a worker (RUNNING), linked to a pipeline that then failed.
    val session =
        f.sessions.create(
            repoFullName = "acme/app",
            taskMarkdown = "t",
            createdBy = "r",
            engine = Engine.Unspecified,
        )
    val claimed = f.sessions.claimNext()
    assertEquals(session.id, claimed?.id)
    val pipeline =
        assertIs<PickResult.Picked>(
                f.pipelines.pick("acme/app", 1, "Issue 1", "https://x/1", session.id),
            )
            .pipeline
    f.pipelines.markFailed(pipeline.id, "boom")

    f.service.clearIssuePipeline(clearIssuePipelineRequest { id = pipeline.id.id })

    // The abandoned session is now FAILED, so a re-pick's worker can't be shadowed by it.
    assertEquals(SessionState.Failed, f.sessions.get(session.id, afterSeq = 0)?.session?.state)
  }

  @Test
  fun `clearing a pipeline whose session is already terminal is a no-op on the session`() =
      runBlocking {
        val f = newFixture()
        // No session with this id exists — clear must still succeed without erroring.
        val pipeline = f.pipelines.pickA()
        f.pipelines.markFailed(pipeline.id, "boom")

        val response =
            f.service.clearIssuePipeline(clearIssuePipelineRequest { id = pipeline.id.id })

        assertTrue(response.pipeline.cleared)
      }

  @Test
  fun `clearIssuePipeline rejects a non-FAILED pipeline with FAILED_PRECONDITION`() = runBlocking {
    val f = newFixture()
    val pipeline = f.pipelines.pickA() // IN_PROGRESS

    val failure =
        assertFailsWith<StatusRuntimeException> {
          f.service.clearIssuePipeline(clearIssuePipelineRequest { id = pipeline.id.id })
        }
    assertEquals(Status.Code.FAILED_PRECONDITION, failure.status.code)
  }

  @Test
  fun `clearIssuePipeline on an unknown id is NOT_FOUND`() = runBlocking {
    val f = newFixture()

    val failure =
        assertFailsWith<StatusRuntimeException> {
          f.service.clearIssuePipeline(clearIssuePipelineRequest { id = "does-not-exist" })
        }
    assertEquals(Status.Code.NOT_FOUND, failure.status.code)
  }

  @Test
  fun `a stuck outbox entry flags outbox_stuck on its pipeline`() = runBlocking {
    val f = newFixture()
    val pipeline = f.pipelines.pickA()
    // markFailed enqueues GitHub side effects (flow:failed label, failure comment). Fail one enough
    // times to cross the stuck threshold.
    f.pipelines.markFailed(pipeline.id, "boom")
    repeat(GithubOutboxStore.defaultStuckAttempts) {
      val head = f.outbox.dueEntries("acme/app").first()
      f.outbox.markFailed(head.id, "github down", Duration.ZERO)
    }

    val row =
        f.service
            .listIssuePipelines(ListIssuePipelinesRequest.getDefaultInstance())
            .pipelinesList
            .single()

    assertTrue(row.outboxStuck)
  }

  @Test
  fun `watchIssuePipelines emits a transition when a pipeline's state changes`() = runBlocking {
    val f = newFixture()
    val pipeline = f.pipelines.pickA()

    withTimeout(5000) {
      coroutineScope {
        val transitionDeferred = async {
          f.service.watchIssuePipelines(watchIssuePipelinesRequest {}).first {
            it.pipeline.id == pipeline.id.id
          }
        }
        // Give the watch's first poll a chance to seed its baseline before the pipeline changes,
        // so the assertion below exercises a genuine *transition* rather than a first-poll replay.
        delay(50)
        f.pipelines.markPrOpen(pipeline.id, prNumber = 7, prUrl = "https://x/pr/7")

        val transition = transitionDeferred.await()
        assertEquals(IssuePipelineState.ISSUE_PIPELINE_STATE_IN_PROGRESS, transition.oldState)
        assertEquals(IssuePipelineState.ISSUE_PIPELINE_STATE_PR_OPEN, transition.pipeline.state)
        assertEquals("https://x/pr/7", transition.pipeline.prUrl)
      }
    }
  }

  @Test
  fun `watchIssuePipelines does not replay pipelines that already existed when the watch started`() =
      runBlocking {
        val f = newFixture()
        f.pipelines.pickA()

        val sawAnything =
            withTimeoutOrNull(200) {
              f.service.watchIssuePipelines(watchIssuePipelinesRequest {}).first()
              true
            }

        assertNull(sawAnything)
      }
}
