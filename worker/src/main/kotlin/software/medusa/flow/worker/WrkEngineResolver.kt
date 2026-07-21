package software.medusa.flow.worker

import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.v1.Engine

/**
 * Routes a claimed session to the [HrsTaskCompleter] for its requested engine.
 *
 * Workers are uniform — every worker can run every engine — so the resolver holds a completer for
 * each engine statically; there is no capability set and no "missing entry" failure mode. A
 * session's `engine` is `UNSPECIFIED` when the creator didn't pin one; those run on [builtin], the
 * classic Flow engine (cheap, and the manifest-gated default).
 */
class WrkEngineResolver(
    private val builtin: HrsTaskCompleter,
    private val claude: HrsTaskCompleter,
) {
  fun resolve(
      engine: Engine,
  ): HrsTaskCompleter =
      when (engine) {
        Engine.ENGINE_CLAUDE -> claude
        Engine.ENGINE_BUILTIN,
        Engine.ENGINE_UNSPECIFIED -> builtin
        else -> error("Unrecognized engine $engine")
      }
}
