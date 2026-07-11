package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [GitHubCandidateClient] backed by GitHub's GraphQL search via [GitHubAppClient]. One query
 * returns every open `ready` issue *and* its blocked-by states (the spike confirmed this is a
 * single call, ~1 rate-limit point); the zero-open-blockers filter is applied client-side.
 */
class GitHubAppCandidateClient(
    private val client: GitHubAppClient,
) : GitHubCandidateClient {
  override suspend fun findReadyCandidates(
      repoFullName: String,
  ): List<CandidateIssue> {
    val searchQuery =
        "repo:$repoFullName is:issue is:open label:${GitHubCandidateClient.readyLabel}"

    val graphQlQuery =
        """
        query {
          search(query: "$searchQuery", type: ISSUE, first: 50) {
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
}

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
