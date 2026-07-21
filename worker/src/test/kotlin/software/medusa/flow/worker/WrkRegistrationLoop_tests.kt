package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import software.medusa.flow.v1.Engine

class WrkRegistrationLoop_tests {
  @Test
  fun `registers the worker's identity and engines, and keeps re-registering`() = runBlocking {
    val apiClient = WrkFakeApiClient()
    val loop =
        WrkRegistrationLoop(
            apiClient = apiClient,
            identity =
                WrkWorkerIdentity(
                    workerId = "w-test",
                    workerVersion = "9.9.9",
                    imageDigest = "sha256:cafe",
                ),
            supportedEngines = listOf(Engine.ENGINE_CLAUDE),
            // Small positive interval so `delay` actually suspends and yields (delay(0) would
            // spin).
            intervalMillis = 1,
            log = {},
        )

    val job = launch { loop.run() }

    fun registrations() =
        apiClient.recordedCalls.filterIsInstance<WrkFakeApiClient.RecordedCall.RegisterWorker>()

    // Wait for repeated registrations, then stop the loop.
    withTimeout(2_000) { while (registrations().size < 2) delay(2) }
    job.cancel()

    val first = registrations().first()
    assertTrue(registrations().size >= 2)
    assertEquals("w-test", first.workerId)
    assertEquals("9.9.9", first.workerVersion)
    assertEquals("sha256:cafe", first.imageDigest)
    assertEquals(listOf(Engine.ENGINE_CLAUDE), first.supportedEngines)
  }
}
