package software.medusa.flow.systemtest

/**
 * Worker version-skew assessment (M5 story 04) — the standing assumption's known hole made visible.
 * The admin-run worker executes whatever image it was last restarted into, so a worker-breaking
 * merge passes the gate until the worker is bumped. This turns that silent gap into a printed fact:
 * the gate states which worker build it tested against and whether it matches the build under
 * promotion.
 *
 * **Advisory, not blocking (M5):** skew produces a loud warning, never a gate failure. The fix —
 * auto-reloading the worker onto a new profile revision — is the Workload project's host-design
 * debate; this only makes the skew legible.
 */
enum class SkewVerdict {
  /** Worker build == the build under promotion. */
  UP_TO_DATE,

  /** Worker build differs from the build under promotion — a worker-side change may be untested. */
  SKEWED,

  /** The worker reported no version (pre-04 worker, or a bare-jar run with no stamp). */
  UNKNOWN_WORKER_VERSION,

  /**
   * No expected version was provided (ad-hoc run outside the gate) — nothing to compare against.
   */
  NO_EXPECTED,
}

data class SkewAssessment(
    val verdict: SkewVerdict,
    /** One-line human summary, ready to print. */
    val summary: String,
    /** True when the gate should print a loud, attention-grabbing warning. */
    val loud: Boolean,
)

object WorkerVersionSkew {
  /**
   * Compares the live worker's [workerVersion] against the [expectedVersion] (the build under
   * promotion — the trunk commit sha the gate is running for). Pure, so the verdict is unit-tested
   * without a live environment.
   */
  fun assess(
      workerVersion: String,
      expectedVersion: String?,
  ): SkewAssessment {
    val expected = expectedVersion?.takeIf { it.isNotBlank() }
    val worker = workerVersion.takeIf { it.isNotBlank() }

    return when {
      expected == null ->
          SkewAssessment(
              SkewVerdict.NO_EXPECTED,
              "worker version=${worker ?: "unknown"}; no expected version to compare against " +
                  "(set EXPECTED_WORKER_VERSION in the gate).",
              loud = false,
          )
      worker == null ->
          SkewAssessment(
              SkewVerdict.UNKNOWN_WORKER_VERSION,
              "worker reported no version — cannot assess skew against expected=$expected. " +
                  "Bump the worker to a build that stamps FLOW_WORKER_VERSION.",
              loud = true,
          )
      worker == expected ->
          SkewAssessment(
              SkewVerdict.UP_TO_DATE,
              "worker version=$worker matches the build under promotion — no skew.",
              loud = false,
          )
      else ->
          SkewAssessment(
              SkewVerdict.SKEWED,
              "WORKER VERSION SKEW: the staging worker is running $worker, but the build under " +
                  "promotion is $expected. A worker-side change in this promotion is NOT covered " +
                  "by this gate run until an admin bumps the worker. (Advisory — the gate still " +
                  "passes.)",
              loud = true,
          )
    }
  }

  private const val expectedVersionEnvVarName = "EXPECTED_WORKER_VERSION"

  /** Reads the expected (under-promotion) version from the environment; null/blank ⇒ absent. */
  fun expectedVersionFromEnv(
      lookup: (String) -> String? = System::getenv,
  ): String? = lookup(expectedVersionEnvVarName)?.takeIf { it.isNotBlank() }
}
