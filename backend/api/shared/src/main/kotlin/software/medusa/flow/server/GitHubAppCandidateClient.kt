package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * [GitHubCandidateClient] backed by GitHub's GraphQL search via [GitHubAppClient]. One query
 * returns every open `flow:ready` issue *and* its blocked-by states (the spike confirmed this is a
 * single call, ~1 rate-limit point); the zero-open-blockers filter is applied client-side.
 *
 * Every query also fetches the `Priority` Issue Field (public preview, 2026-03-12 — see
 * [IssuePriority]) unconditionally: the query fragment (`issueField(name: "Priority")`, `... on
 * IssueFieldSingleSelectValue`) is this integration's best-effort read of the preview schema. A
 * schema mismatch degrades safely rather than breaking candidate discovery: [findReadyCandidates]
 * checks the GraphQL response for a top-level `errors` array and retries once with the field
 * fragment omitted, logging a warning so the mismatch is visible — candidates still come back, just
 * with [CandidateIssue.priorityField] unset (never derived from a label).
 */
class GitHubAppCandidateClient(
    private val client: GitHubAppClient,
) : GitHubCandidateClient {
  private val log = LoggerFactory.getLogger(GitHubAppCandidateClient::class.java)

  override suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue> = findReadyCandidates(repoFullName, includePriorityField = true)

  private suspend fun findReadyCandidates(
      repoFullName: String,
      includePriorityField: Boolean,
  ): List<CandidateIssue> {
    // The `\"` are literal in this raw string; they quote the label (which contains a colon) inside
    // the GraphQL search-query string. The JSON encoder below escapes them for transport.
    val priorityFieldFragment =
        if (includePriorityField) {
          """
          issueField(name: "Priority") {
            ... on IssueFieldSingleSelectValue { name }
          }
          """
              .trimIndent()
        } else {
          ""
        }
    val graphQlQuery =
        """
        query {
          search(query: "repo:$repoFullName is:issue is:open label:\"${GitHubCandidateClient.readyLabel}\"", type: ISSUE, first: 50) {
            nodes {
              ... on Issue {
                number
                title
                body
                url
                createdAt
                blockedBy(first: 50) { nodes { state } }
                labels(first: 20) { nodes { name } }
                $priorityFieldFragment
              }
            }
          }
        }
        """
            .trimIndent()

    val body = gitHubJson.encodeToString(buildJsonObject { put("query", graphQlQuery) })
    val response = client.post("/graphql", body)
    check(response.status() == HttpStatus.OK) {
      "GitHub candidate search failed for $repoFullName: ${response.status()} ${response.contentUtf8()}"
    }

    val envelope = gitHubJson.decodeFromString<CandidateEnvelope>(response.contentUtf8())

    // A GraphQL-level error (e.g. the priority-field fragment doesn't match the live schema) comes
    // back as HTTP 200 with an `errors` array, not a failed status — so it isn't caught by the
    // check
    // above. Retry once without the field fragment rather than letting an unverified preview-API
    // fragment take down candidate discovery entirely.
    if (!envelope.errors.isNullOrEmpty()) {
      if (includePriorityField) {
        log.warn(
            "Issue Fields query failed for {} ({}) — retrying without the priority field fragment;" +
                " see GitHubAppCandidateClient's KDoc to fix the fragment",
            repoFullName,
            envelope.errors,
        )
        return findReadyCandidates(repoFullName, includePriorityField = false)
      }
      error("GitHub candidate search returned GraphQL errors for $repoFullName: ${envelope.errors}")
    }

    val issues = envelope.data?.search?.nodes.orEmpty().filterNotNull()

    return issues
        .filter { issue ->
          issue.blockedBy?.nodes.orEmpty().filterNotNull().none { it.state == "OPEN" }
        }
        .map {
          CandidateIssue(
              number = it.number,
              title = it.title,
              body = it.body.orEmpty(),
              url = it.url,
              createdAt = Instant.parse(it.createdAt),
              labels =
                  it.labels?.nodes.orEmpty().filterNotNull().mapNotNull { l -> l.name }.toSet(),
              priorityField = it.issueField?.name,
          )
        }
        .sortedBy { it.createdAt } // oldest first
  }

  override suspend fun findReposWithReadyIssues(): Set<String> {
    // Scope to our own org with `org:`. The GraphQL search is NOT limited to the App's installed
    // repos — with only `label:"flow:ready"` it matches any public repo on GitHub that happens to
    // use that label name. `org:` fences it to our org; the owner-prefix filter below is a
    // belt-and-suspenders guard in case the qualifier ever returns something broader.
    val owner = client.repoOwner
    val graphQlQuery =
        """
        query {
          search(query: "org:$owner is:issue is:open label:\"${GitHubCandidateClient.readyLabel}\"", type: ISSUE, first: 100) {
            nodes {
              ... on Issue {
                repository { nameWithOwner }
              }
            }
          }
        }
        """
            .trimIndent()

    val body = gitHubJson.encodeToString(buildJsonObject { put("query", graphQlQuery) })
    val response = client.post("/graphql", body)
    check(response.status() == HttpStatus.OK) {
      "GitHub ready-repo discovery search failed: ${response.status()} ${response.contentUtf8()}"
    }

    return gitHubJson
        .decodeFromString<RepoDiscoveryEnvelope>(response.contentUtf8())
        .data
        ?.search
        ?.nodes
        .orEmpty()
        .filterNotNull()
        .mapNotNull { it.repository?.nameWithOwner }
        .filter { it.substringBefore('/') == owner }
        .toSet()
  }
}

@Serializable private data class RepoDiscoveryEnvelope(val data: RepoDiscoveryData? = null)

@Serializable private data class RepoDiscoveryData(val search: RepoDiscoverySearch? = null)

@Serializable private data class RepoDiscoverySearch(val nodes: List<RepoDiscoveryNode?>? = null)

@Serializable private data class RepoDiscoveryNode(val repository: RepoDiscoveryRepo? = null)

@Serializable private data class RepoDiscoveryRepo(val nameWithOwner: String? = null)

@Serializable
private data class CandidateEnvelope(
    val data: CandidateData? = null,
    val errors: List<JsonElement>? = null,
)

@Serializable private data class CandidateData(val search: CandidateSearch? = null)

@Serializable private data class CandidateSearch(val nodes: List<CandidateNode?>? = null)

@Serializable
private data class CandidateNode(
    val number: Int,
    val title: String,
    val body: String? = null,
    val url: String,
    val createdAt: String,
    val blockedBy: CandidateBlockedBy? = null,
    val labels: CandidateLabels? = null,
    /**
     * The `Priority` Issue Field's value, present only when the query included the priority-field
     * fragment and the field resolved to a single-select value. `name` here is that fragment's
     * inline `... on IssueFieldSingleSelectValue { name }` flattened by the GraphQL server — absent
     * (null) for any other concrete type or an unset field.
     */
    val issueField: CandidateIssueField? = null,
)

@Serializable private data class CandidateBlockedBy(val nodes: List<CandidateBlocker?>? = null)

@Serializable private data class CandidateBlocker(val state: String? = null)

@Serializable private data class CandidateLabels(val nodes: List<CandidateLabel?>? = null)

@Serializable private data class CandidateIssueField(val name: String? = null)

@Serializable private data class CandidateLabel(val name: String? = null)
