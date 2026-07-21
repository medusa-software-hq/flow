package software.medusa.flow.systemtest

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The worker-liveness preflight as a loop-tier test (story 01): asserts staging has a live
 * registered worker and prints the version it saw. With the admin worker running it is green; with
 * the worker stopped it fails with the distinct "staging worker down" message (a
 * [StagingWorkerDownException], not a timeout soup), satisfying story 01's acceptance.
 *
 * Later loop-tier tests (story 03) call [WorkerLiveness.requireLiveWorker] themselves as their
 * first step, so an outage short-circuits them with the same distinct signal before any credits are
 * spent.
 */
class WorkerLivenessPreflightTest : SystemTestBase() {
  @Loop
  @Test
  fun stagingHasALiveRegisteredWorker() = runBlocking {
    val worker = WorkerLiveness.requireLiveWorker(clients, ::log)

    assertTrue(worker.workerId.isNotBlank()) {
      "a live worker must carry a non-blank id; got $worker"
    }
  }
}
