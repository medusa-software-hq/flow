package software.medusa.flow.worker

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.filesystem.materializeIn
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace

/**
 * Branches, syncs the materialized workspace over the clone, commits, pushes, and opens a PR via
 * the GitHub REST API, per design/04-observability-and-github-layering.md.
 */
class WrkProperGitHubPublisher(
    private val tokenSupplierFactory: WrkGitHubTokenSupplierFactory,
    private val webClient: WebClient = WebClient.of(githubApiBaseUrl),
) : WrkPublisher {
  companion object {
    private const val githubApiBaseUrl = "https://api.github.com"
    private const val githubAcceptHeader = "application/vnd.github+json"
    private const val userAgent = "medusa-flow-worker"
    private const val authorName = "Flow Worker"
    private const val authorEmail = "flow-worker@users.noreply.github.com"

    private val json = Json { ignoreUnknownKeys = true }
  }

  override suspend fun publish(
      repoFullName: String,
      sessionId: String,
      taskHeading: String,
      taskMarkdown: String,
      cloneDirectory: Path,
      workspace: HrsReadonlyTemporaryWorkspace,
      issueNumber: Int?,
  ): WrkPublishResult {
    // One refreshing installation-token supplier for this session's repo, shared by the git pushes
    // and the PR-create call below; each use resolves a current token.
    val gitHubToken = tokenSupplierFactory.forRepo(repoFullName)
    val directoryFile = cloneDirectory.toFile()
    val branchName =
        if (issueNumber != null) "flow/issue-$issueNumber" else "flow/session-$sessionId"

    val defaultBranch =
        WrkGitProcess.run(directoryFile, gitHubToken, "rev-parse", "--abbrev-ref", "HEAD").trim()

    WrkGitProcess.run(directoryFile, gitHubToken, "checkout", "-b", branchName)

    syncWorkspaceInto(cloneDirectory, workspace)

    WrkGitProcess.run(directoryFile, gitHubToken, "add", "-A")

    val diffResult =
        WrkGitProcess.runAllowingFailure(directoryFile, gitHubToken, "diff", "--cached", "--quiet")

    val hasChanges =
        when (diffResult.exitCode) {
          0 -> false
          1 -> true
          else -> error("git diff failed (exit ${diffResult.exitCode}):\n${diffResult.output}")
        }

    if (!hasChanges) return WrkPublishResult.NoChanges

    val commitBody = "Session: $sessionId"

    WrkGitProcess.run(
        directoryFile,
        gitHubToken,
        "-c",
        "user.name=$authorName",
        "-c",
        "user.email=$authorEmail",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-m",
        taskHeading,
        "-m",
        commitBody,
    )

    WrkGitProcess.run(directoryFile, gitHubToken, "push", "origin", branchName)

    val prUrl =
        createPullRequest(
            repoFullName = repoFullName,
            branchName = branchName,
            baseBranch = defaultBranch,
            title = taskHeading,
            body =
                composePrBody(
                    taskMarkdown = taskMarkdown,
                    sessionId = sessionId,
                    issueNumber = issueNumber,
                ),
            gitHubToken = gitHubToken,
        )

    return WrkPublishResult.Published(prUrl = prUrl)
  }

  /**
   * A manual session's body is byte-identical to M1. An issue-linked body embeds the (sanitized)
   * task Markdown, then a `Refs #<n>` that references the issue *without* closing it — closing is
   * the reconciler's gated move.
   */
  private fun composePrBody(
      taskMarkdown: String,
      sessionId: String,
      issueNumber: Int?,
  ): String =
      if (issueNumber == null) {
        "$taskMarkdown\n\n---\nSession: $sessionId"
      } else {
        val safeMarkdown = WrkClosingKeywords.neutralize(taskMarkdown)
        "$safeMarkdown\n\n---\nRefs #$issueNumber\nSession: $sessionId"
      }

  private suspend fun syncWorkspaceInto(
      cloneDirectory: Path,
      workspace: HrsReadonlyTemporaryWorkspace,
  ) {
    withContext(Dispatchers.IO) {
      Files.newDirectoryStream(cloneDirectory).use { children ->
        children
            .filter { it.fileName.toString() != ".git" }
            .forEach { child -> child.toFile().deleteRecursively() }
      }
    }

    workspace.rootDirectory.materializeIn(
        targetDirectory = UfsNioDirectory(directoryPath = cloneDirectory),
    )
  }

  private suspend fun createPullRequest(
      repoFullName: String,
      branchName: String,
      baseBranch: String,
      title: String,
      body: String,
      gitHubToken: suspend () -> String,
  ): String {
    val requestBody =
        json.encodeToString(
            CreatePullRequestBody.serializer(),
            CreatePullRequestBody(title = title, head = branchName, base = baseBranch, body = body),
        )

    val response =
        webClient
            .prepare()
            .post("/repos/$repoFullName/pulls")
            .header(HttpHeaderNames.AUTHORIZATION, "Bearer ${gitHubToken()}")
            .header(HttpHeaderNames.ACCEPT, githubAcceptHeader)
            .header(HttpHeaderNames.USER_AGENT, userAgent)
            // NB: `.content(MediaType, String)`, NOT `.content(String, Object...)` -- the latter is
            // Armeria's printf overload, which would drop `requestBody` and send the format string.
            .content(MediaType.JSON, requestBody)
            .execute()
            .aggregate()
            .await()

    check(response.status() == HttpStatus.CREATED) {
      "GitHub PR creation failed for $repoFullName: ${response.status()} ${response.contentUtf8()}"
    }

    return json.decodeFromString(PullRequestDto.serializer(), response.contentUtf8()).htmlUrl
  }
}

@Serializable
private data class CreatePullRequestBody(
    val title: String,
    val head: String,
    val base: String,
    val body: String,
)

@Serializable private data class PullRequestDto(@SerialName("html_url") val htmlUrl: String)
