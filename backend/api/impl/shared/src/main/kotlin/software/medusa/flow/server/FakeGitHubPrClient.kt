package software.medusa.flow.server

/** A scriptable [GitHubPrClient] for tests: PR state per PR number, merge status per commit sha. */
class FakeGitHubPrClient : GitHubPrClient {
  val prStateByNumber = mutableMapOf<Int, PullRequestState>()
  val mergeStatusBySha = mutableMapOf<String, MergeCheckStatus>()

  override suspend fun getPullRequestState(
      repoFullName: String,
      prNumber: Int,
  ): PullRequestState = prStateByNumber[prNumber] ?: PullRequestState.Open

  override suspend fun getMergeCheckStatus(
      repoFullName: String,
      commitSha: String,
  ): MergeCheckStatus = mergeStatusBySha[commitSha] ?: MergeCheckStatus.NoRuns
}
