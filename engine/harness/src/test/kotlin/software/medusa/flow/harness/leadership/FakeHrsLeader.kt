package software.medusa.flow.harness.leadership

/**
 * A scripted [HrsLeader]: replays [results] one per successive [decide] call (the last one repeats
 * past the end, so a single-element script stands in for "always decide the same way"), and records
 * every [decide] call's [HrsLeaderContext] (in order) in [invocations] — for tests of a future
 * executor (story 06) that only need to assert *what* it did with a given leader decision, not how
 * the leader itself decides.
 */
class FakeHrsLeader(
    private val results: List<HrsLeader.Result>,
) : HrsLeader {
  init {
    require(results.isNotEmpty()) { "results must not be empty" }
  }

  val invocations: MutableList<HrsLeaderContext> = mutableListOf()

  override suspend fun decide(
      context: HrsLeaderContext,
  ): HrsLeader.Result {
    invocations += context
    return results[(invocations.size - 1).coerceAtMost(results.size - 1)]
  }
}
