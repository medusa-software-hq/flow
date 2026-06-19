package software.medusa.flow.integration.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class GrdApi_tests {
  @Test
  fun `task result stores captured outputs`() {
    val result =
        GrdTaskResult(
            status = GrdTaskResult.Status.Success,
            standardOutput = "ok",
            errorOutput = "",
        )

    assertEquals("ok", result.standardOutput)
    assertEquals(GrdTaskResult.Status.Success, result.status)
  }
}
