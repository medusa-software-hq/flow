package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WrkClosingKeywords_tests {
  @Test
  fun `neutralizes closing keywords before every reference form`() {
    val cases =
        listOf(
            "Closes #12",
            "closed #12",
            "Fixes #12",
            "fixed: #12",
            "Resolves #12",
            "resolve owner/repo#12",
            "Closes https://github.com/owner/repo/issues/12",
        )

    for (input in cases) {
      val output = WrkClosingKeywords.neutralize(input)
      assertFalse(
          WrkClosingKeywords.containsClosingReference(output),
          "still closes after neutralize: '$output'",
      )
      // Rendered text (zero-width space stripped) is unchanged.
      assertEquals(input, output.replace("​", ""))
    }
  }

  @Test
  fun `leaves a bare Refs reference untouched`() {
    val input = "See Refs #12 and also #34 for context."
    assertEquals(input, WrkClosingKeywords.neutralize(input))
  }

  @Test
  fun `does not treat a keyword embedded in another word as closing`() {
    // "disclosed" contains "closed" but isn't a word-boundary keyword.
    val input = "The plan was disclosed #12 times."
    assertEquals(input, WrkClosingKeywords.neutralize(input))
    assertFalse(WrkClosingKeywords.containsClosingReference(input))
  }

  @Test
  fun `a keyword with no following reference is left alone`() {
    val input = "This fixes the flaky test but tracks nothing."
    assertEquals(input, WrkClosingKeywords.neutralize(input))
  }

  @Test
  fun `neutralizes multiple references in one body`() {
    val output = WrkClosingKeywords.neutralize("Fixes #1 and closes owner/repo#2.")
    assertFalse(WrkClosingKeywords.containsClosingReference(output))
    assertTrue(output.contains("#1"))
    assertTrue(output.contains("owner/repo#2"))
  }
}
