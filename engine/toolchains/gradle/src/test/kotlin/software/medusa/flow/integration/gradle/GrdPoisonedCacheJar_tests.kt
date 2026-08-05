package software.medusa.flow.integration.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GrdPoisonedCacheJar_tests {
  @Test
  fun `extracts the jar path from a poisoned cache read failure`() {
    val buildOutput =
        """
        * What went wrong:
        Execution failed for task ':compileKotlin'.
        > Could not resolve all files for configuration ':compileClasspath'.
           > Could not read file: /home/flow/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.0/deadbeef/kotlin-stdlib-2.4.0.jar!/kotlin/collections/ArraysKt___ArraysKt.class
        """
            .trimIndent()

    val poisonedJar = findPoisonedCacheJar(buildOutput)

    assertEquals(
        expected =
            "/home/flow/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib/2.4.0/deadbeef/kotlin-stdlib-2.4.0.jar",
        actual = poisonedJar?.path,
    )
  }

  @Test
  fun `returns null for a failure unrelated to the cache`() {
    val buildOutput =
        """
        * What went wrong:
        Execution failed for task ':compileKotlin'.
        > Compilation error. See log for more details
        """
            .trimIndent()

    assertNull(findPoisonedCacheJar(buildOutput))
  }
}
