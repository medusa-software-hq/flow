package software.medusa.flow.worker

import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.v1.Engine

/**
 * Routes a claimed session to the [HrsTaskCompleter] for its requested engine (M4).
 *
 * A session's `engine` is `UNSPECIFIED` when the creator didn't pin one; those run on
 * [defaultEngine] — the first engine in the worker's `FLOW_WORKER_ENGINES` capability list. A named
 * engine (`BUILTIN`/`CLAUDE`) routes to its own completer. The control plane's claim filter (A2)
 * guarantees a worker is only ever handed sessions whose engine it declared, so a missing entry
 * here is a wiring bug and fails loudly.
 */
class WrkEngineResolver(
    private val completersByEngine: Map<Engine, HrsTaskCompleter>,
    private val defaultEngine: Engine,
) {
  init {
    require(completersByEngine.containsKey(defaultEngine)) {
      "The default engine $defaultEngine has no configured task completer " +
          "(configured: ${completersByEngine.keys})."
    }
  }

  fun resolve(
      engine: Engine,
  ): HrsTaskCompleter {
    val effectiveEngine = if (engine == Engine.ENGINE_UNSPECIFIED) defaultEngine else engine
    return completersByEngine[effectiveEngine]
        ?: error(
            "No task completer configured for engine $effectiveEngine " +
                "(configured: ${completersByEngine.keys}).",
        )
  }
}
