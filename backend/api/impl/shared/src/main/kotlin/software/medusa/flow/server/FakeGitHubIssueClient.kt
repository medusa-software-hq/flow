package software.medusa.flow.server

/**
 * A recording [GitHubIssueClient] for tests. Captures every call in order and can be scripted to
 * fail specific operations, so the dispatcher's ordering, backoff, and idempotent-replay behavior
 * can be asserted without a real GitHub.
 */
class FakeGitHubIssueClient : GitHubIssueClient {
  sealed interface Call {
    data class EnsureLabels(val repoFullName: String) : Call

    data class AddLabel(val repoFullName: String, val issueNumber: Int, val label: String) : Call

    data class RemoveLabel(val repoFullName: String, val issueNumber: Int, val label: String) : Call

    data class PostComment(val repoFullName: String, val issueNumber: Int, val body: String) : Call

    data class CloseIssue(val repoFullName: String, val issueNumber: Int) : Call
  }

  val calls = mutableListOf<Call>()

  /** When it returns non-null, the call throws with that message instead of recording success. */
  var failureFor: (Call) -> String? = { null }

  private fun run(
      call: Call,
  ) {
    failureFor(call)?.let { throw RuntimeException(it) }
    calls += call
  }

  override suspend fun ensureLabelsExist(repoFullName: String) =
      run(Call.EnsureLabels(repoFullName))

  override suspend fun addLabel(repoFullName: String, issueNumber: Int, label: String) =
      run(Call.AddLabel(repoFullName, issueNumber, label))

  override suspend fun removeLabel(repoFullName: String, issueNumber: Int, label: String) =
      run(Call.RemoveLabel(repoFullName, issueNumber, label))

  override suspend fun postComment(repoFullName: String, issueNumber: Int, body: String) =
      run(Call.PostComment(repoFullName, issueNumber, body))

  override suspend fun closeIssue(repoFullName: String, issueNumber: Int) =
      run(Call.CloseIssue(repoFullName, issueNumber))
}
