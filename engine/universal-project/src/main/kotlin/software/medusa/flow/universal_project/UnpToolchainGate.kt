package software.medusa.flow.universal_project

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Process-wide concurrency limiter for heavy toolchain phases, keyed by toolchain name.
 *
 * The worker runs several sessions concurrently — the Claude/built-in fan-out claims a whole job
 * and runs its sessions in parallel. Their AI work is cheap and network-bound, so it is never
 * routed through here and stays fully parallel. Their **toolchain** phases are the opposite:
 *
 * - Two independent Gradle builds sharing one `GRADLE_USER_HOME` corrupt each other's
 *   artifact-transform / instrumentation cache (`Could not deserialize ... instrumentation-*.bin`,
 *   `BUILD FAILED in 2s`) — Gradle's shared-cache locking does not make two full concurrent builds
 *   safe in practice.
 * - Several forked build/test JVMs at once exhaust the worker VM's memory.
 *
 * So this gate bounds how many phases of a given toolchain execute at once. Gradle is
 * **serialized** (1) by default, which lets the concurrent sessions share one cache *sequentially*
 * — correct, and cheaper than isolating a cache per session on a small disk. Node is allowed a few
 * (4), since its work is lighter. Permits are overridable via env for tuning per host.
 *
 * The gate is a process singleton on purpose: it models a per-worker-machine resource. It does not
 * coordinate across processes, so two co-located workers (e.g. staging + prod on one host) each get
 * their own budget — the "two max spikes" the host is sized to absorb.
 */
object UnpToolchainGate {
  private val semaphoreByToolchain: Map<String, Semaphore> =
      mapOf(
          "gradle" to Semaphore(permits = envPermits("FLOW_TOOLCHAIN_GRADLE_PERMITS", default = 1)),
          "nodejs" to Semaphore(permits = envPermits("FLOW_TOOLCHAIN_NODEJS_PERMITS", default = 4)),
      )

  /**
   * Runs [block] while holding a permit for [toolchain]. A toolchain with no configured limit runs
   * ungated (never blocks) — gating is opt-in per known toolchain.
   */
  suspend fun <T> gated(
      toolchain: String,
      block: suspend () -> T,
  ): T {
    val semaphore = semaphoreByToolchain[toolchain] ?: return block()
    return semaphore.withPermit { block() }
  }

  private fun envPermits(
      name: String,
      default: Int,
  ): Int = System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: default
}
