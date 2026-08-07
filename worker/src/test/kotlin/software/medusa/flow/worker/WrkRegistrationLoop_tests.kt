package software.medusa.flow.worker

import io.grpc.Status
import io.grpc.StatusException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
  }

  @Test
  fun `UNAUTHENTICATED forces a fresh credential on every retry and eventually trips the watchdog`() =
      runBlocking {
        val apiClient = WrkFakeApiClient()
        apiClient.registerWorkerFailure = { StatusException(Status.UNAUTHENTICATED) }

        val wedged = CompletableDeferred<Duration>()
        val watchdog =
            WrkAuthWedgeWatchdog(
                threshold = Duration.ofMillis(5),
                onWedged = { wedged.complete(it) },
            )

        val loop =
            WrkRegistrationLoop(
                apiClient = apiClient,
                identity =
                    WrkWorkerIdentity(
                        workerId = "w-test",
                        workerVersion = "9.9.9",
                        imageDigest = "sha256:cafe",
                    ),
                intervalMillis = 1,
                authWedgeWatchdog = watchdog,
                log = {},
            )

        val job = launch { loop.run() }
        withTimeout(5_000) { wedged.await() }
        job.cancel()

        assertTrue(
            apiClient.recordedCalls.count {
              it == WrkFakeApiClient.RecordedCall.InvalidateCredentials
            } >= 1,
            "each UNAUTHENTICATED retry must force a fresh credential fetch, not replay the cached one",
        )
      }
}
