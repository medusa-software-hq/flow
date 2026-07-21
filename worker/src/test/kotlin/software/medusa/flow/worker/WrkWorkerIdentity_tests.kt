package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WrkWorkerIdentity_tests {
  @Test
  fun `explicit FLOW_WORKER_ID and version are honoured`() {
    val identity =
        WrkWorkerIdentity.fromEnvironment(
            mapOf(
                "FLOW_WORKER_ID" to "staging-worker-1",
                "FLOW_WORKER_VERSION" to "2.4.0",
                "FLOW_WORKER_IMAGE_DIGEST" to "sha256:deadbeef",
            )::get,
        )

    assertEquals("staging-worker-1", identity.workerId)
    assertEquals("2.4.0", identity.workerVersion)
    assertEquals("sha256:deadbeef", identity.imageDigest)
  }

  @Test
  fun `worker id falls back to a non-blank default and version defaults to empty`() {
    val identity = WrkWorkerIdentity.fromEnvironment { null }

    assertTrue(identity.workerId.isNotBlank())
    assertEquals("", identity.workerVersion)
    assertEquals("", identity.imageDigest)
  }
}
