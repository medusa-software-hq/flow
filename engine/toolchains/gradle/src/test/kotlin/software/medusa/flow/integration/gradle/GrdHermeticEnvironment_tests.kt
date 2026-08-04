package software.medusa.flow.integration.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class GrdHermeticEnvironment_tests {
  @Test
  fun `strips GCE_METADATA_ vars leaking Beacon into the build`() {
    val environment =
        grdHermeticEnvironment(
            lookup = {
              mapOf(
                  "GCE_METADATA_HOST" to "192.168.64.4:39621",
                  "GCE_METADATA_IP" to "192.168.64.4:39621",
                  "GCE_METADATA_ROOT" to "192.168.64.4:39621",
                  "PATH" to "/usr/bin",
              )
            },
        )

    assertEquals(expected = mapOf("PATH" to "/usr/bin"), actual = environment)
  }

  @Test
  fun `passes through everything else unchanged`() {
    val hostEnvironment =
        mapOf("PATH" to "/usr/bin", "HOME" to "/home/flow", "JAVA_HOME" to "/opt/java/openjdk")

    val environment = grdHermeticEnvironment(lookup = { hostEnvironment })

    assertEquals(expected = hostEnvironment, actual = environment)
  }
}
