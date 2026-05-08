package software.medusa.flow.core_service.flows

import software.medusa.git.GitCommitHash

sealed interface TaskResult {
  val outputCommitHash: GitCommitHash
}

data class FeatureTaskResult(
    val featureCommitHash: GitCommitHash,
) : TaskResult {
  override val outputCommitHash: GitCommitHash
    get() = featureCommitHash
}

data class MergeTaskResult(
    val mergeCommitHash: GitCommitHash,
) : TaskResult {
  override val outputCommitHash: GitCommitHash
    get() = mergeCommitHash
}
