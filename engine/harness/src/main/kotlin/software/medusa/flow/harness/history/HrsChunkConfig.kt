package software.medusa.flow.harness.history

/**
 * The two compaction constants, centralized (and logged per run): a **small chunk** is
 * [smallChunkSize] consecutive delegations (`s` = 2–4); a **big chunk** is [bigChunkSize]
 * consecutive small chunks (`B` = 8). Chunk-aligned tiers are what keep the leader's rendered
 * history byte-stable between closes — it changes only once per `s` delegations (small) or `B·s`
 * (big).
 */
data class HrsChunkConfig(
    val smallChunkSize: Int = 3,
    val bigChunkSize: Int = 8,
) {
  init {
    require(smallChunkSize >= 1) { "smallChunkSize must be positive" }
    require(bigChunkSize >= 1) { "bigChunkSize must be positive" }
  }

  companion object {
    val default = HrsChunkConfig()
  }
}
