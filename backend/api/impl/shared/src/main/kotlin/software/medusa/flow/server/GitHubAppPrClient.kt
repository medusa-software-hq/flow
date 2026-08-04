package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [GitHubPrClient] backed by GitHub via [GitHubAppClient]. PR state comes from REST; the merge gate
 * uses the GraphQL `statusCheckRollup` + `checkSuites` the spike validated as giving the complete
 * Actions picture and distinguishing "no runs" (rollup absent) from "pending".
 */
class GitHubAppPrClient(
    private val client: GitHubAppClient,
) : GitHubPrClient {
  override suspend fun getPullRequestState(
      repoFullName: String,
      prNumber: Int,
  ): PullRequestState {
    val response = client.get("/repos/$repoFullName/pulls/$prNumber")
    check(response.status() == HttpStatus.OK) {
      "GitHub get-PR failed for $repoFullName#$prNumber: ${response.status()} ${response.contentUtf8()}"
    }

    val pr = gitHubJson.decodeFromString<MergeGatePullRequestDto>(response.contentUtf8())
    return when {
      pr.merged && pr.mergeCommitSha != null -> PullRequestState.Merged(pr.mergeCommitSha)
      pr.state == "closed" -> PullRequestState.ClosedUnmerged
      else -> PullRequestState.Open
    }
  }

  override suspend fun getMergeCheckStatus(
      repoFullName: String,
      commitSha: String,
  ): MergeCheckStatus {
    val (owner, name) = repoFullName.split("/", limit = 2).let { it[0] to it[1] }

    val query =
        """
        query {
          repository(owner: "$owner", name: "$name") {
            object(oid: "$commitSha") {
              ... on Commit {
                statusCheckRollup { state }
                checkSuites(first: 100) {
                  nodes { conclusion workflowRun { workflow { name } } }
                }
              }
            }
          }
        }
        """
            .trimIndent()

    val body = gitHubJson.encodeToString(buildJsonObject { put("query", query) })
    val response = client.post("/graphql", body)
    check(response.status() == HttpStatus.OK) {
      "GitHub merge-gate query failed for $repoFullName@$commitSha: " +
          "${response.status()} ${response.contentUtf8()}"
    }

    val commit =
        gitHubJson
            .decodeFromString<MergeGateEnvelope>(response.contentUtf8())
            .data
            ?.repository
            ?.`object` ?: return MergeCheckStatus.NoRuns

    // Rollup absent ⇒ no checks are associated with the commit at all.
    val rollupState = commit.statusCheckRollup?.state ?: return MergeCheckStatus.NoRuns

    return when (rollupState) {
      "SUCCESS" -> MergeCheckStatus.Green
      "PENDING",
      "EXPECTED" -> MergeCheckStatus.Pending
      // FAILURE / ERROR
      else -> MergeCheckStatus.Red(failingRunNames = failingRunNames(commit))
    }
  }

  private fun failingRunNames(
      commit: MergeGateCommit,
  ): List<String> =
      commit.checkSuites
          ?.nodes
          .orEmpty()
          .filter { it.conclusion in failedConclusions }
          .map { it.workflowRun?.workflow?.name ?: "unknown workflow" }
          .ifEmpty { listOf("unknown check") }

  /**
   * `enablePullRequestAutoMerge` takes the PR's opaque GraphQL node id, not its number, so this
   * first resolves the id then issues the mutation. Never throws: any failure — HTTP-level, a
   * GraphQL `errors` payload (the common case: auto-merge disabled for the repo, or no branch
   * protection / required checks configured), or an unexpected response shape — becomes
   * [AutoMergeResult.Failed] with a human-readable reason.
   */
  override suspend fun armAutoMerge(
      repoFullName: String,
      prNumber: Int,
      mergeMethod: MergeMethod,
  ): AutoMergeResult =
      try {
        val (owner, name) = repoFullName.split("/", limit = 2).let { it[0] to it[1] }

        val lookupQuery =
            """
            query {
              repository(owner: "$owner", name: "$name") {
                pullRequest(number: $prNumber) { id }
              }
            }
            """
                .trimIndent()
        val lookupResponse =
            client.post(
                "/graphql",
                gitHubJson.encodeToString(buildJsonObject { put("query", lookupQuery) }),
            )
        if (lookupResponse.status() != HttpStatus.OK) {
          return AutoMergeResult.Failed(
              "PR lookup failed: ${lookupResponse.status()} ${lookupResponse.contentUtf8()}",
          )
        }

        val lookup =
            gitHubJson.decodeFromString<AutoMergeLookupEnvelope>(lookupResponse.contentUtf8())
        val prNodeId =
            lookup.data?.repository?.pullRequest?.id
                ?: return AutoMergeResult.Failed(
                    "could not resolve PR node id for $repoFullName#$prNumber" +
                        lookup.errors
                            ?.let { errs -> ": ${errs.joinToString("; ") { it.message }}" }
                            .orEmpty(),
                )

        val mutation =
            """
            mutation {
              enablePullRequestAutoMerge(input: {
                pullRequestId: "$prNodeId",
                mergeMethod: ${mergeMethod.toGraphQl()}
              }) {
                pullRequest { id }
              }
            }
            """
                .trimIndent()
        val mutationResponse =
            client.post(
                "/graphql",
                gitHubJson.encodeToString(buildJsonObject { put("query", mutation) }),
            )
        if (mutationResponse.status() != HttpStatus.OK) {
          return AutoMergeResult.Failed(
              "enablePullRequestAutoMerge failed: " +
                  "${mutationResponse.status()} ${mutationResponse.contentUtf8()}",
          )
        }

        val mutationResult =
            gitHubJson.decodeFromString<AutoMergeMutationEnvelope>(mutationResponse.contentUtf8())
        val errors = mutationResult.errors
        if (!errors.isNullOrEmpty()) {
          return AutoMergeResult.Failed(errors.joinToString("; ") { it.message })
        }

        AutoMergeResult.Armed
      } catch (e: Exception) {
        AutoMergeResult.Failed(e.message ?: e::class.simpleName ?: "unknown error")
      }

  private fun MergeMethod.toGraphQl(): String =
      when (this) {
        MergeMethod.Merge -> "MERGE"
        MergeMethod.Squash -> "SQUASH"
        MergeMethod.Rebase -> "REBASE"
      }

  private companion object {
    private val failedConclusions = setOf("FAILURE", "CANCELLED", "TIMED_OUT", "STARTUP_FAILURE")
  }
}

@Serializable
private data class MergeGatePullRequestDto(
    val state: String,
    val merged: Boolean = false,
    @SerialName("merge_commit_sha") val mergeCommitSha: String? = null,
)

@Serializable private data class MergeGateEnvelope(val data: MergeGateData? = null)

@Serializable private data class MergeGateData(val repository: MergeGateRepository? = null)

@Serializable private data class MergeGateRepository(val `object`: MergeGateCommit? = null)

@Serializable
private data class MergeGateCommit(
    val statusCheckRollup: MergeGateRollup? = null,
    val checkSuites: MergeGateCheckSuites? = null,
)

@Serializable private data class MergeGateRollup(val state: String? = null)

@Serializable private data class MergeGateCheckSuites(val nodes: List<MergeGateCheckSuite>? = null)

@Serializable
private data class MergeGateCheckSuite(
    val conclusion: String? = null,
    val workflowRun: MergeGateWorkflowRun? = null,
)

@Serializable private data class MergeGateWorkflowRun(val workflow: MergeGateWorkflow? = null)

@Serializable private data class MergeGateWorkflow(val name: String? = null)

@Serializable
private data class AutoMergeLookupEnvelope(
    val data: AutoMergeLookupData? = null,
    val errors: List<AutoMergeGraphQlError>? = null,
)

@Serializable
private data class AutoMergeLookupData(val repository: AutoMergeLookupRepository? = null)

@Serializable
private data class AutoMergeLookupRepository(val pullRequest: AutoMergeLookupPullRequest? = null)

@Serializable private data class AutoMergeLookupPullRequest(val id: String)

@Serializable
private data class AutoMergeMutationEnvelope(val errors: List<AutoMergeGraphQlError>? = null)

@Serializable private data class AutoMergeGraphQlError(val message: String)
