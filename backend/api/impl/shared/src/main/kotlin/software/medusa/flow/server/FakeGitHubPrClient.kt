package software.medusa.flow.server

/** A scriptable [GitHubPrClient] for tests: PR state per PR number, merge status per commit sha. */
class FakeGitHubPrClient : GitHubPrClient {
  val prStateByNumber = mutableMapOf<Int, PullRequestState>()
  val mergeStatusBySha = mutableMapOf<String, MergeCheckStatus>()

  /** Scripts [armAutoMerge]'s result per PR number; absent defaults to [AutoMergeResult.Armed]. */
  val autoMergeResultByNumber = mutableMapOf<Int, AutoMergeResult>()

  /** Every `(prNumber, mergeMethod)` pair [armAutoMerge] was called with, in call order. */
  val autoMergeCalls = mutableListOf<Pair<Int, MergeMethod>>()

  override suspend fun getPullRequestState(
      repoFullName: String,
      prNumber: Int,
  ): PullRequestState = prStateByNumber[prNumber] ?: PullRequestState.Open

  override suspend fun getMergeCheckStatus(
      repoFullName: String,
      commitSha: String,
  ): MergeCheckStatus = mergeStatusBySha[commitSha] ?: MergeCheckStatus.NoRuns

  override suspend fun armAutoMerge(
      repoFullName: String,
      prNumber: Int,
      mergeMethod: MergeMethod,
  ): AutoMergeResult {
    autoMergeCalls += prNumber to mergeMethod
    return autoMergeResultByNumber[prNumber] ?: AutoMergeResult.Armed
  }
}
