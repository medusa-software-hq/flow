package software.medusa.flow.server

import java.time.Instant

/** A pickable issue: open, `ready`-labeled, with zero *open* blockers. */
data class CandidateIssue(
    val number: Int,
    val title: String,
    val body: String,
    val url: String,
    val createdAt: Instant,
)

/**
 * The read side of GitHub used by the reconcile pick phase: discovers pickable issues. A port with
 * a real impl ([GitHubAppCandidateClient]) and a scriptable [FakeGitHubCandidateClient].
 */
interface GitHubCandidateClient {
  /**
   * Open, `ready`-labeled issues in [repoFullName] whose blocked-by set contains zero *open* issues
   * (a closed blocker no longer blocks — GitHub keeps the edge but flips its state; see the spike),
   * oldest-first. The label is the opt-in; no repo allowlist.
   */
  suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue>

  companion object {
    /** The opt-in label (name hardcoded in M2). */
    const val readyLabel = "ready"
  }
}
