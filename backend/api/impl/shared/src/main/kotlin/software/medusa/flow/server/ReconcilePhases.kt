package software.medusa.flow.server

/**
 * The observe phase of a reconcile for one repo: reads PR / merge-check state from GitHub and
 * advances live pipelines (`PR_OPEN → AWAITING_MERGE_CHECKS → DONE | FAILED`). Returns how many
 * pipelines advanced. Filled in by story 06; a [Noop] stub for the skeleton.
 */
fun interface PipelineObserver {
  suspend fun observe(
      repoFullName: String,
  ): Int

  companion object {
    val Noop = PipelineObserver { 0 }
  }
}

/**
 * The pick phase of a reconcile for one repo: if the repo is free, picks the next unblocked,
 * `ready`-labeled issue and starts a pipeline. Returns how many were picked (0 or 1 in M2). Filled
 * in by story 07; a [Noop] stub for the skeleton.
 */
fun interface PipelinePicker {
  suspend fun pick(
      repoFullName: String,
  ): Int

  companion object {
    val Noop = PipelinePicker { 0 }
  }
}
