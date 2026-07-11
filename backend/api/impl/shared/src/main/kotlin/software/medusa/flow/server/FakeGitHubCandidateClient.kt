package software.medusa.flow.server

/**
 * A scriptable [GitHubCandidateClient] for tests. Returns the configured [candidatesByRepo]
 * (already filtered to unblocked, `ready` issues — the client's real impl does that server-/client-
 * side, so the picker never sees blocked ones).
 */
class FakeGitHubCandidateClient : GitHubCandidateClient {
  val candidatesByRepo = mutableMapOf<String, List<CandidateIssue>>()

  override suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue> = candidatesByRepo[repoFullName].orEmpty()
}
