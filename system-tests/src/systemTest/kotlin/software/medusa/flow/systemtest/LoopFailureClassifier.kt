package software.medusa.flow.systemtest

/**
 * Classifies a FAILED loop-tier session's `failureSummary` as a **transient** model/network flake
 * (worth one retry) or a **real** failure (fail the gate now). Pure, so the marker set is
 * unit-tested without a live environment.
 *
 * A transient failure is one the LLM/provider produced non-deterministically — a cut-short
 * response, `finish_reason=error`, a rate limit — which a fresh session usually clears. Everything
 * else (a genuine engine give-up, a build/verify failure, an unexpected worker error) is a real
 * regression the promotion gate must surface, so the default is **not transient**.
 */
object LoopFailureClassifier {
  /**
   * Case-insensitive substrings that mark a transient failure. Seeded from the failure observed
   * during M5 verification (`OaiIncompleteResponseException` / `finish_reason=error`) plus the
   * usual model/network transients. Keep this conservative — a false "transient" wastes a retry and
   * can mask a real regression, so only add signatures that are unambiguously provider-side flakes.
   */
  val transientMarkers: List<String> =
      listOf(
          "finish_reason=error",
          "was cut short",
          "incomplete and was discarded",
          "oaiincompleteresponseexception",
          "rate limit",
          "rate-limit",
          "429 too many requests",
          "overloaded",
          "service unavailable",
          "connection reset",
          "read timed out",
      )

  fun isTransient(
      failureSummary: String,
  ): Boolean {
    val lower = failureSummary.lowercase()
    return transientMarkers.any { lower.contains(it) }
  }
}
