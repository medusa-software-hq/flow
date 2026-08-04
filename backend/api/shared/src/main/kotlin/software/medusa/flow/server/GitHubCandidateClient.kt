package software.medusa.flow.server

import java.time.Instant

/** A pickable issue: open, `flow:ready`-labeled, with zero *open* blockers. */
data class CandidateIssue(
    val number: Int,
    val title: String,
    val body: String,
    val url: String,
    val createdAt: Instant,
    /**
     * The issue's label names. No longer used to pick an engine (dual-engine fan-out runs both);
     * read for [IssuePriority]'s label-fallback queue ordering during the Issue Fields grace
     * window.
     */
    val labels: Set<String> = emptySet(),
    /**
     * The `Priority` Issue Field's selected option name (e.g. `"Urgent"`), or `null` if unset or
     * unread (see [GitHubAppCandidateClient]'s `readPriorityField`). Authoritative for
     * [IssuePriority] over [labels] when present.
     */
    val priorityField: String? = null,
)

/**
 * The read side of GitHub used by the reconcile pick phase: discovers pickable issues. A port with
 * a real impl ([GitHubAppCandidateClient]) and a scriptable [FakeGitHubCandidateClient].
 */
interface GitHubCandidateClient {
  /**
   * Open, `flow:ready`-labeled issues in [repoFullName] whose blocked-by set contains zero *open*
   * issues (a closed blocker no longer blocks — GitHub keeps the edge but flips its state; see the
   * spike), oldest-first. The label is the opt-in; no repo allowlist.
   */
  suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue>

  /**
   * Every repo the App installation can see that currently has at least one open `flow:ready` issue
   * — the scheduler's repo-discovery source, so a fresh repo's first pick doesn't depend on a
   * webhook. One installation-scoped search, no per-repo enumeration.
   */
  suspend fun findReposWithReadyIssues(): Set<String>

  companion object {
    /** The opt-in label (name hardcoded in M2), namespaced like the other `flow:*` labels. */
    const val readyLabel = "flow:ready"
  }
}
