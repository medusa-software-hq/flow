package software.medusa.flow.worker

/**
 * Bounds the volume of `AgentAction` events one session emits (M4 claude engine). A chatty tool
 * stream can produce hundreds of actions per run; without a bound every one would become a stored
 * session event, blowing past the display-only event contract.
 *
 * The rule is a simple per-session cap: the first [maxEvents] actions pass through verbatim; the
 * action that would exceed the cap is replaced, exactly once, by a single truncation notice; every
 * action after that is dropped. So a run emits at most [maxEvents] action events, however chatty it
 * is. Kept a pure, single-purpose unit so the coalescing behaviour is testable without a live run.
 */
class WrkAgentActionCoalescer(
    private val maxEvents: Int = defaultMaxAgentActionEvents,
) {
  companion object {
    /**
     * Default per-session cap on emitted `AgentAction` events. Mirrors the control plane's
     * display-only contract (`SessionStore.maxAgentActionEvents`); kept as a local constant because
     * the worker module doesn't depend on the backend store.
     */
    const val defaultMaxAgentActionEvents = 200
  }

  /** What to do with an offered action summary. */
  sealed interface Decision {
    /** Emit [message] as an `AgentAction` event. */
    data class Emit(
        val message: String,
    ) : Decision

    /** Suppress the action entirely (the cap is already spent). */
    data object Drop : Decision
  }

  private var emitted = 0
  private var noticeSent = false

  /** Decides whether [summary] should be emitted, replaced by a cap notice, or dropped. */
  fun offer(
      summary: String,
  ): Decision {
    if (emitted < maxEvents) {
      emitted++
      return Decision.Emit(summary)
    }
    if (!noticeSent) {
      noticeSent = true
      return Decision.Emit("… further agent actions omitted (reached the $maxEvents-event cap).")
    }
    return Decision.Drop
  }
}
