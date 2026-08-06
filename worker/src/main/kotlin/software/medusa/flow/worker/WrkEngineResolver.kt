package software.medusa.flow.worker

import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.v1.Engine

/**
 * Routes a claimed session to the [HrsTaskCompleter] for its requested engine.
 *
 * Workers are uniform — every worker can run every engine — so the resolver holds a completer for
 * each engine statically; there is no capability set and no "missing entry" failure mode. A
 * session's `engine` is `UNSPECIFIED` when the creator didn't pin one; those run on [default],
 * which is [leader] (the primary engine as of M3-12) unless the caller overrides it (see
 * `FLOW_WORKER_ENGINE=builtin|claude|leader` in `main.kt` — the knob for pointing a worker's
 * unspecified-engine sessions at `builtin` or `claude` instead, both of which remain selectable
 * indefinitely as fallbacks, M3-09/M3-11/M3-12).
 */
class WrkEngineResolver(
    private val builtin: HrsTaskCompleter,
    private val claude: HrsTaskCompleter,
    private val leader: HrsTaskCompleter,
    private val default: HrsTaskCompleter = leader,
) {
  fun resolve(
      engine: Engine,
  ): HrsTaskCompleter =
      when (engine) {
        Engine.ENGINE_CLAUDE -> claude
        Engine.ENGINE_BUILTIN -> builtin
        Engine.ENGINE_LEADER -> leader
        Engine.ENGINE_UNSPECIFIED -> default
        else -> error("Unrecognized engine $engine")
      }
}
