package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [GitHubCandidateClient] backed by GitHub's GraphQL search via [GitHubAppClient]. One query
 * returns every open `flow:ready` issue *and* its blocked-by states (the spike confirmed this is a
 * single call, ~1 rate-limit point); the zero-open-blockers filter is applied client-side.
 */
class GitHubAppCandidateClient(
    private val client: GitHubAppClient,
) : GitHubCandidateClient {
  override suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue> {
    // The `\"` are literal in this raw string; they quote the label (which contains a colon) inside
    // the GraphQL search-query string. The JSON encoder below escapes them for transport.
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

    val issues =
        gitHubJson
            .decodeFromString<CandidateEnvelope>(response.contentUtf8())
            .data
            ?.search
            ?.nodes
            .orEmpty()
            .filterNotNull()

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

@Serializable private data class CandidateEnvelope(val data: CandidateData? = null)

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
)

@Serializable private data class CandidateBlockedBy(val nodes: List<CandidateBlocker?>? = null)

@Serializable private data class CandidateBlocker(val state: String? = null)
