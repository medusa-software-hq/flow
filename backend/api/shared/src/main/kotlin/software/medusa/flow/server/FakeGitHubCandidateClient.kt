package software.medusa.flow.server

/**
 * A scriptable [GitHubCandidateClient] for tests. Returns the configured [candidatesByRepo]
 * (already filtered to unblocked, `ready` issues — the client's real impl does that server-/client-
 * side, so the picker never sees blocked ones).
 */
class FakeGitHubCandidateClient : GitHubCandidateClient {
  val candidatesByRepo = mutableMapOf<String, List<CandidateIssue>>()

  /** Repos [findReposWithReadyIssues] reports; defaults to those with configured candidates. */
  var reposWithReadyIssues: Set<String>? = null

  override suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue> = candidatesByRepo[repoFullName].orEmpty()

  override suspend fun findReposWithReadyIssues(): Set<String> =
      reposWithReadyIssues ?: candidatesByRepo.filterValues { it.isNotEmpty() }.keys
}
