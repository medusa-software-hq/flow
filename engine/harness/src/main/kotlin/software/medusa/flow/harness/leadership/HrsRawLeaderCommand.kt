package software.medusa.flow.harness.leadership

import kotlinx.schema.Description
import kotlinx.serialization.Serializable

/**
 * The raw, LLM-facing shape of a leader command — a flat structured-output payload (the leader
 * emits structured output, never tool calls, an accepted project constraint). [toCommand] turns it
 * into the domain [HrsLeaderCommand]; a flat schema (rather than kotlinx polymorphism) matches the
 * `HrsRaw*` convention already used for scout decisions.
 */
@Serializable
data class HrsRawLeaderCommand(
    @Description(
        "True to end the branch: the task is complete, or further work is judged unproductive. When true the other fields are ignored.",
    )
    val stop: Boolean,
    @Description(
        "The delegation's Markdown task definition, given when not stopping. Natural language; may carry exposure guidance and check expectations.",
    )
    val taskDefinition: String = "",
    @Description(
        "Absolute worktree paths to take off the leader board before the delegation starts, exactly as shown, e.g. `/src/Main.kt`. May be empty.",
    )
    val hideList: List<String> = emptyList(),
) {
  fun toCommand(): HrsLeaderCommand =
      when {
        stop -> HrsLeaderCommand.Stop
        else ->
            HrsLeaderCommand.Delegate(
                taskDefinition = HrsTaskDefinition(markdown = taskDefinition),
                hideList = hideList,
            )
      }

  companion object {
    /**
     * The raw payload that serializes to [command] — the inverse of [toCommand] for round-trips.
     */
    fun fromCommand(
        command: HrsLeaderCommand,
    ): HrsRawLeaderCommand =
        when (command) {
          HrsLeaderCommand.Stop -> HrsRawLeaderCommand(stop = true)
          is HrsLeaderCommand.Delegate ->
              HrsRawLeaderCommand(
                  stop = false,
                  taskDefinition = command.taskDefinition.markdown,
                  hideList = command.hideList,
              )
        }
  }
}
