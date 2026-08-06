package software.medusa.flow.harness.history

/**
 * Which tier's close triggered a summary request (story 07) — purely for prompt wording; storage is
 * the same [HrsChunkSummary] shape either way, filed under [HrsDelegationLog.smallChunkSummaries]
 * or [HrsDelegationLog.bigChunkSummaries] depending on which.
 */
enum class HrsChunkSummaryKind {
  SmallChunk,
  BigChunk,
}
