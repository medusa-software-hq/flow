package software.medusa.flow.harness.leadership

import software.medusa.flow.harness.HrsTaskCompleter.Observer

/**
 * The leader/assistant engine's expensive role: one structured-output call per turn over a curated,
 * bounded context (see [HrsProperLeader]). Never throws to report a stuck turn — a persistently
 * malformed or refused structured response ends the turn with [Result.Failed] instead, mirroring
 * the honest-failure shape [software.medusa.flow.harness.assistance.HrsAssistant] uses for a stuck
 * delegation thread.
 */
interface HrsLeader {
  /** One leader turn's outcome. */
  sealed interface Result {
    /** The leader produced a valid [HrsLeaderCommand]. */
    data class Decided(
        val command: HrsLeaderCommand,
    ) : Result

    /** Every attempt at a valid structured command was malformed or refused. */
    data class Failed(
        val reason: String,
    ) : Result
  }

  /**
   * Renders [context] into the leader prompt and asks for a single structured [HrsLeaderCommand].
   * [observer] receives this turn's debug-level raw traffic (see
   * [Observer.observeRawLeaderResponse]); it defaults to [Observer.Noop] for callers that don't
   * care.
   */
  suspend fun decide(
      context: HrsLeaderContext,
      observer: Observer = Observer.Noop,
  ): Result
}
