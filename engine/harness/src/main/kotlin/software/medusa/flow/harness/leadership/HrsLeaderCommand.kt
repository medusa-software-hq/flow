package software.medusa.flow.harness.leadership

/**
 * The single command a leader turn produces. The leader drives the loop but never acts directly: it
 * either hands off a [Delegate] to the assistant or [Stop]s. Leader turn count ≈ delegation count ≈
 * the milestone's cost driver.
 *
 * This is the clean domain type; its LLM-facing (structured-output) shape is [HrsRawLeaderCommand].
 */
sealed interface HrsLeaderCommand {
  /**
   * Hand a delegation to the assistant. [hideList] is a *deterministic* buffer cleanup applied
   * mechanically before the delegation starts (hiding needs no judgment, so it does not deserve a
   * round); exposing, which does, stays an assistant tool. Entries are absolute worktree paths.
   */
  data class Delegate(
      val taskDefinition: HrsTaskDefinition,
      val hideList: List<String> = emptyList(),
  ) : HrsLeaderCommand

  /** End the branch — the task is complete (or the leader judges further work unproductive). */
  data object Stop : HrsLeaderCommand
}
