package software.medusa.flow.systemtest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit coverage for the version-skew verdict. Carries no tier tag and does not touch staging,
 * so it runs whenever `:system-tests:systemTest` runs (untagged), with no environment.
 */
class WorkerVersionSkew_tests {
  @Test
  fun `matching versions are up to date and quiet`() {
    val a = WorkerVersionSkew.assess(workerVersion = "abc123", expectedVersion = "abc123")
    assertEquals(SkewVerdict.UP_TO_DATE, a.verdict)
    assertFalse(a.loud)
  }

  @Test
  fun `differing versions are a loud skew`() {
    val a = WorkerVersionSkew.assess(workerVersion = "old-sha", expectedVersion = "new-sha")
    assertEquals(SkewVerdict.SKEWED, a.verdict)
    assertTrue(a.loud)
    assertTrue(a.summary.contains("old-sha") && a.summary.contains("new-sha"))
  }

  @Test
  fun `an unknown worker version with an expected version is loud`() {
    val a = WorkerVersionSkew.assess(workerVersion = "", expectedVersion = "new-sha")
    assertEquals(SkewVerdict.UNKNOWN_WORKER_VERSION, a.verdict)
    assertTrue(a.loud)
  }

  @Test
  fun `no expected version is quiet (ad-hoc run)`() {
    val a = WorkerVersionSkew.assess(workerVersion = "abc123", expectedVersion = null)
    assertEquals(SkewVerdict.NO_EXPECTED, a.verdict)
    assertFalse(a.loud)

    val blank = WorkerVersionSkew.assess(workerVersion = "abc123", expectedVersion = "  ")
    assertEquals(SkewVerdict.NO_EXPECTED, blank.verdict)
  }

  @Test
  fun `expectedVersionFromEnv trims blanks to null`() {
    assertEquals("sha", WorkerVersionSkew.expectedVersionFromEnv { "sha" })
    assertEquals(null, WorkerVersionSkew.expectedVersionFromEnv { "" })
    assertEquals(null, WorkerVersionSkew.expectedVersionFromEnv { null })
  }
}
