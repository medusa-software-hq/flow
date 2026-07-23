package software.medusa.flow.worker

import io.grpc.Status
import io.grpc.StatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import software.medusa.flow.v1.Session
import software.medusa.flow.v1.session

class WrkPollLoop_tests {
  // Each session defaults to its own 1-session job, so these claim one at a time; the parallel test
  // below shares a job id across two sessions on purpose.
  private fun testSession(id: String, jobId: String = "$id-job"): Session = session {
    this.id = id
    this.jobId = jobId
  }

  @Test
  fun `claims and processes sessions one at a time, then keeps polling`() = runBlocking {
    val apiClient = WrkFakeApiClient()
    apiClient.enqueue(testSession("s1"))
    apiClient.enqueue(testSession("s2"))

    val processedIds = mutableListOf<String>()

    val processor = WrkSessionProcessor { session, client ->
      processedIds.add(session.id)
      client.completeSession(sessionId = session.id, prUrl = "https://example.com/${session.id}")
    }

    val pollLoop =
        WrkPollLoop(
            apiClient = apiClient,
            sessionProcessor = processor,
            emptyPollDelayMillis = 5,
            log = {},
        )

    val job = launch { pollLoop.run() }

    // Wait until both sessions are processed, then cancel the (otherwise infinite) loop.
    while (processedIds.size < 2) yield()
    job.cancel()
    job.join()

    assertEquals(listOf("s1", "s2"), processedIds)
    assertTrue(
        apiClient.recordedCalls.any {
          it is WrkFakeApiClient.RecordedCall.CompleteSession && it.sessionId == "s1"
        },
    )
  }

  @Test
  fun `claims a whole job and runs its sessions in parallel`() = runBlocking {
    val apiClient = WrkFakeApiClient()
    // Two sessions of one job — a reconciled issue's Claude + built-in engines.
    apiClient.enqueue(testSession("claude", jobId = "job-1"))
    apiClient.enqueue(testSession("builtin", jobId = "job-1"))

    val started = java.util.Collections.synchronizedList(mutableListOf<String>())
    val processed = java.util.Collections.synchronizedList(mutableListOf<String>())

    val processor = WrkSessionProcessor { session, _ ->
      started.add(session.id)
      // Neither finishes until BOTH have started: if the worker processed the job strictly
      // sequentially this would deadlock (the second never starts), so reaching here proves the two
      // engines run concurrently under one claim.
      while (started.size < 2) yield()
      processed.add(session.id)
    }

    val pollLoop =
        WrkPollLoop(
            apiClient = apiClient,
            sessionProcessor = processor,
            emptyPollDelayMillis = 5,
            log = {},
        )

    val job = launch { pollLoop.run() }
    while (processed.size < 2) yield()
    job.cancel()
    job.join()

    assertEquals(setOf("claude", "builtin"), processed.toSet())
  }

  @Test
  fun `a processor exception is reported via failSession, and polling continues`() = runBlocking {
    val apiClient = WrkFakeApiClient()
    apiClient.enqueue(testSession("boom-session"))
    apiClient.enqueue(testSession("next-session"))

    val processedIds = mutableListOf<String>()

    val processor = WrkSessionProcessor { session, _ ->
      if (session.id == "boom-session") error("simulated failure")
      processedIds.add(session.id)
    }

    val pollLoop =
        WrkPollLoop(
            apiClient = apiClient,
            sessionProcessor = processor,
            emptyPollDelayMillis = 5,
            log = {},
        )

    val job = launch { pollLoop.run() }

    while (processedIds.size < 1) yield()
    job.cancel()
    job.join()

    val failCall =
        apiClient.recordedCalls
            .filterIsInstance<WrkFakeApiClient.RecordedCall.FailSession>()
            .single()
    assertEquals("boom-session", failCall.sessionId)
    assertTrue(failCall.failureSummary.contains("simulated failure"))
  }

  @Test
  fun `FAILED_PRECONDITION from the processor is logged and abandoned, not retried as a failure`() =
      runBlocking {
        val apiClient = WrkFakeApiClient()
        apiClient.enqueue(testSession("stale-session"))
        apiClient.enqueue(testSession("next-session"))

        val processedIds = mutableListOf<String>()

        val processor = WrkSessionProcessor { session, _ ->
          if (session.id == "stale-session") {
            throw StatusException(Status.FAILED_PRECONDITION)
          }
          processedIds.add(session.id)
        }

        val pollLoop =
            WrkPollLoop(
                apiClient = apiClient,
                sessionProcessor = processor,
                emptyPollDelayMillis = 5,
                log = {},
            )

        val job = launch { pollLoop.run() }

        while (processedIds.size < 1) yield()
        job.cancel()
        job.join()

        assertTrue(
            apiClient.recordedCalls
                .filterIsInstance<WrkFakeApiClient.RecordedCall.FailSession>()
                .none { it.sessionId == "stale-session" },
        )
      }
}
