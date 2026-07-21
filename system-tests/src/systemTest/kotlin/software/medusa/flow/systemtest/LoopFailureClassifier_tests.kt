package software.medusa.flow.systemtest

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure coverage for the transient/real failure split. Carries no tier tag and touches no
 * environment, so it runs whenever `:system-tests:systemTest` runs (untagged), with no config.
 */
class LoopFailureClassifier_tests {
  @Test
  fun `the observed model cut-short failure is transient`() {
    // Verbatim shape of the failure that flaked the M5 verification run.
    val summary =
        "Worker encountered an unexpected error:\n\n```\n" +
            "software.medusa.commons.openai_client.OaiIncompleteResponseException: OpenAI response " +
            "was cut short (finish_reason=error); its content is incomplete and was discarded\n```"
    assertTrue(LoopFailureClassifier.isTransient(summary))
  }

  @Test
  fun `rate limits and provider outages are transient`() {
    assertTrue(
        LoopFailureClassifier.isTransient("HTTP 429 Too Many Requests from the model provider")
    )
    assertTrue(LoopFailureClassifier.isTransient("upstream returned 503 Service Unavailable"))
    assertTrue(LoopFailureClassifier.isTransient("the model is Overloaded, try again"))
  }

  @Test
  fun `a real engine or build failure is not transient`() {
    assertFalse(
        LoopFailureClassifier.isTransient(
            "Initial analysis failed:\n\nFAILURE: Build failed with an exception. compileJava"
        )
    )
    assertFalse(
        LoopFailureClassifier.isTransient(
            "The engine exhausted its retry budget without a healthy result"
        )
    )
    assertFalse(LoopFailureClassifier.isTransient(""))
  }

  @Test
  fun `matching is case-insensitive`() {
    assertTrue(LoopFailureClassifier.isTransient("FINISH_REASON=ERROR"))
  }
}
