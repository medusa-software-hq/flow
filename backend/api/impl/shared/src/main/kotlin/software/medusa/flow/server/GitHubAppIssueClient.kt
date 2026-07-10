package software.medusa.flow.server

import com.linecorp.armeria.common.HttpStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * [GitHubIssueClient] backed by the GitHub REST API via [GitHubAppClient]. The App installation
 * token is org-scoped, so this drives any repo under the installation — [repoFullName] is passed
 * per call, not fixed to the client's discovery repo.
 */
class GitHubAppIssueClient(
    private val client: GitHubAppClient,
) : GitHubIssueClient {
  override suspend fun ensureLabelsExist(
      repoFullName: String,
  ) {
    GitHubIssueClient.flowLabelColors.forEach { (name, color) ->
      val body =
          gitHubJson.encodeToString(
              buildJsonObject {
                put("name", name)
                put("color", color)
              },
          )

      val response = client.post("/repos/$repoFullName/labels", body)

      // 201 created, or 422 when it already exists — both are success for an idempotent ensure.
      check(response.status() == HttpStatus.CREATED || response.status().code() == 422) {
        "GitHub create-label failed for $name in $repoFullName: " +
            "${response.status()} ${response.contentUtf8()}"
      }
    }
  }

  override suspend fun addLabel(
      repoFullName: String,
      issueNumber: Int,
      label: String,
  ) {
    val body = gitHubJson.encodeToString(buildJsonObject { putJsonArray("labels") { add(label) } })
    val response = client.post("/repos/$repoFullName/issues/$issueNumber/labels", body)

    check(response.status() == HttpStatus.OK) {
      "GitHub add-label failed for $label on $repoFullName#$issueNumber: " +
          "${response.status()} ${response.contentUtf8()}"
    }
  }

  override suspend fun removeLabel(
      repoFullName: String,
      issueNumber: Int,
      label: String,
  ) {
    val encoded = java.net.URLEncoder.encode(label, Charsets.UTF_8)
    val response = client.delete("/repos/$repoFullName/issues/$issueNumber/labels/$encoded")

    // 200 removed, 404 the label wasn't on the issue — both fine for an idempotent remove.
    check(response.status() == HttpStatus.OK || response.status() == HttpStatus.NOT_FOUND) {
      "GitHub remove-label failed for $label on $repoFullName#$issueNumber: " +
          "${response.status()} ${response.contentUtf8()}"
    }
  }

  override suspend fun postComment(
      repoFullName: String,
      issueNumber: Int,
      body: String,
  ) {
    val requestBody = gitHubJson.encodeToString(buildJsonObject { put("body", body) })
    val response = client.post("/repos/$repoFullName/issues/$issueNumber/comments", requestBody)

    check(response.status() == HttpStatus.CREATED) {
      "GitHub post-comment failed on $repoFullName#$issueNumber: " +
          "${response.status()} ${response.contentUtf8()}"
    }
  }

  override suspend fun closeIssue(
      repoFullName: String,
      issueNumber: Int,
  ) {
    val body = gitHubJson.encodeToString(buildJsonObject { put("state", "closed") })
    val response = client.patch("/repos/$repoFullName/issues/$issueNumber", body)

    check(response.status() == HttpStatus.OK) {
      "GitHub close-issue failed on $repoFullName#$issueNumber: " +
          "${response.status()} ${response.contentUtf8()}"
    }
  }
}
