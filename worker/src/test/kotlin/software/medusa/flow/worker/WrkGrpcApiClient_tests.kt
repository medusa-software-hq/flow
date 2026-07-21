package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertNotNull

class WrkGrpcApiClient_tests {
  /**
   * The hermetic loop test runs the shipped worker binary against a local control plane with no
   * Google credentials anywhere. Without the skip seam, [WrkGrpcApiClient.create] resolves
   * Application Default Credentials and blows up before the first call.
   *
   * The inverse (that an unset seam *does* reach for ADC) is deliberately not asserted: whether ADC
   * resolves depends on the machine — it would pass locally and fail in CI, or vice versa.
   */
  @Test
  fun `the skip-auth seam builds a client without resolving Google credentials`() {
    val client =
        WrkGrpcApiClient.create(
            // Never connected to — building the stub is enough to exercise the auth path.
            apiUrl = "http://127.0.0.1:1",
            lookupEnv = { name -> "1".takeIf { name == "FLOW_TEST_SKIP_API_AUTH" } },
        )

    assertNotNull(client)
  }
}
