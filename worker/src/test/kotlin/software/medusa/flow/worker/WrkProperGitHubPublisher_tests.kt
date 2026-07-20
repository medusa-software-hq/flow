package software.medusa.flow.worker

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace

private fun runGit(
    directory: Path,
    vararg args: String,
) {
  val process =
      ProcessBuilder(listOf("git", "-c", "commit.gpgsign=false") + args)
          .directory(directory.toFile())
          .redirectErrorStream(true)
          .start()

  val output = process.inputStream.bufferedReader().readText()
  val exitCode = process.waitFor()

  check(exitCode == 0) {
    "git ${args.joinToString(" ")} failed in $directory (exit $exitCode):\n$output"
  }
}

private class PublisherFakeReadonlyTemporaryWorkspace(
    override val rootDirectory: UfsReadonlyDirectory,
) : HrsReadonlyTemporaryWorkspace {
  override fun close() = Unit
}

class WrkProperGitHubPublisher_tests {
  private lateinit var server: Server

  @AfterTest
  fun tearDown() {
    if (::server.isInitialized) server.stop().join()
  }

  private data class BareRepoAndClone(
      val bareDirectory: Path,
      val cloneDirectory: Path,
  )

  /**
   * A bare repo standing in for GitHub, plus a local clone of it (as [WrkGitCloner] would make).
   */
  private fun setUpBareRepoAndClone(): BareRepoAndClone {
    val bareDirectory = Files.createTempDirectory("wrk-publish-bare-")
    // `-b main` pins the bare repo's HEAD to the branch we're about to push, regardless of the
    // machine's `init.defaultBranch` -- otherwise a plain push doesn't move HEAD to follow it, and
    // a clone ends up with an unborn HEAD ("ambiguous argument 'HEAD'").
    runGit(bareDirectory, "init", "--bare", "-q", "-b", "main")

    val seedDirectory = Files.createTempDirectory("wrk-publish-seed-")
    runGit(seedDirectory, "init", "-q", "-b", "main")
    seedDirectory.resolve("README.md").toFile().writeText("seed\n")
    runGit(seedDirectory, "add", "-A")
    runGit(
        seedDirectory,
        "-c",
        "user.name=seed",
        "-c",
        "user.email=seed@example.com",
        "commit",
        "-m",
        "seed",
    )
    runGit(seedDirectory, "push", bareDirectory.toString(), "main")

    val cloneDirectory = Files.createTempDirectory("wrk-publish-clone-")
    runGit(
        Files.createTempDirectory("wrk-publish-cwd-"),
        "clone",
        "-q",
        bareDirectory.toString(),
        cloneDirectory.toString(),
    )

    return BareRepoAndClone(bareDirectory = bareDirectory, cloneDirectory = cloneDirectory)
  }

  private fun buildPrCreationServer(
      responseJson: String,
  ): WebClient {
    server =
        Server.builder()
            .http(0)
            .service("/repos/acme/app/pulls") { _, _ ->
              HttpResponse.of(HttpStatus.CREATED, MediaType.JSON, responseJson)
            }
            .build()
            .also { it.start().join() }

    return WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  private suspend fun fakeWorkspaceWith(
      fileName: String,
      content: String,
  ): HrsReadonlyTemporaryWorkspace {
    val directory = UfsNioDirectory.createTemporary(prefix = UfsName.Literal("wrk-publish-ws-"))
    directory.createFile(
        name = UfsName.Literal(fileName),
        initialContent = ByteString(content.toByteArray()),
    )
    return PublisherFakeReadonlyTemporaryWorkspace(rootDirectory = directory)
  }

  @Test
  fun `a run with real changes branches, commits, pushes, and opens a PR`() = runBlocking {
    val (_, cloneDirectory) = setUpBareRepoAndClone()
    val webClient =
        buildPrCreationServer(
            responseJson = """{"html_url":"https://github.com/acme/app/pull/7"}""",
        )

    val workspace = fakeWorkspaceWith(fileName = "hello.txt", content = "hello from the engine\n")

    val publisher =
        WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

    val result =
        publisher.publish(
            repoFullName = "acme/app",
            sessionId = "s1",
            taskHeading = "Add hello.txt",
            taskMarkdown = "# Add hello.txt\n\nDo it.",
            cloneDirectory = cloneDirectory,
            workspace = workspace,
            issueNumber = null,
        )

    assertEquals(WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/7"), result)

    // The push actually landed on the "GitHub" (bare) side, on the expected branch name.
    val bareShow =
        ProcessBuilder("git", "show", "flow/session-s1:hello.txt")
            .directory(cloneDirectory.toFile())
            .start()
    val output = bareShow.inputStream.bufferedReader().readText()
    bareShow.waitFor()
    assertTrue(output.contains("hello from the engine"))
  }

  @Test
  fun `an executable file keeps its executable bit through publish`() = runBlocking {
    val (_, cloneDirectory) = setUpBareRepoAndClone()
    val webClient =
        buildPrCreationServer(
            responseJson = """{"html_url":"https://github.com/acme/app/pull/9"}"""
        )

    // A gradlew-like script: executable in the materialized workspace.
    val directory = UfsNioDirectory.createTemporary(prefix = UfsName.Literal("wrk-publish-ws-"))
    directory
        .createFile(
            name = UfsName.Literal("run.sh"),
            initialContent = ByteString("#!/bin/sh\necho hi\n".toByteArray()),
        )
        .makeExecutable()
    val workspace = PublisherFakeReadonlyTemporaryWorkspace(rootDirectory = directory)

    val publisher =
        WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

    publisher.publish(
        repoFullName = "acme/app",
        sessionId = "s9",
        taskHeading = "Add run.sh",
        taskMarkdown = "# Add run.sh",
        cloneDirectory = cloneDirectory,
        workspace = workspace,
        issueNumber = null,
    )

    // git records an executable blob as mode 100755, a regular one as 100644.
    val lsTree =
        ProcessBuilder("git", "ls-tree", "flow/session-s9", "run.sh")
            .directory(cloneDirectory.toFile())
            .start()
    val mode = lsTree.inputStream.bufferedReader().readText()
    lsTree.waitFor()
    assertTrue(mode.startsWith("100755"), "expected an executable blob, got: $mode")
  }

  @Test
  fun `a run with no changes returns NoChanges and pushes nothing`() = runBlocking {
    val (bareDirectory, cloneDirectory) = setUpBareRepoAndClone()
    val webClient = buildPrCreationServer(responseJson = """{"html_url":"unused"}""")

    // Same content as the seeded README -- no diff.
    val workspace = fakeWorkspaceWith(fileName = "README.md", content = "seed\n")

    val publisher =
        WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

    val result =
        publisher.publish(
            repoFullName = "acme/app",
            sessionId = "s2",
            taskHeading = "No-op",
            taskMarkdown = "# No-op",
            cloneDirectory = cloneDirectory,
            workspace = workspace,
            issueNumber = null,
        )

    assertEquals(WrkPublishResult.NoChanges, result)

    // Nothing was ever pushed to the "GitHub" (bare) side -- only the local clone got the branch.
    val branchList =
        ProcessBuilder("git", "branch", "--list").directory(bareDirectory.toFile()).start()
    val output = branchList.inputStream.bufferedReader().readText()
    branchList.waitFor()
    assertTrue(!output.contains("flow/session-s2"))
  }

  @Test
  fun `an issue-linked run branches on the issue and Refs it without a closing keyword`() =
      runBlocking {
        val (_, cloneDirectory) = setUpBareRepoAndClone()
        val webClient =
            buildCapturingPrServer(
                responseJson = """{"html_url":"https://github.com/acme/app/pull/12"}""",
            )

        val workspace =
            fakeWorkspaceWith(fileName = "hello.txt", content = "hello from the engine\n")

        val publisher =
            WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

        val result =
            publisher.publish(
                repoFullName = "acme/app",
                sessionId = "s7",
                taskHeading = "Add hello.txt",
                taskMarkdown = "# Add hello.txt\n\nDo it.",
                cloneDirectory = cloneDirectory,
                workspace = workspace,
                issueNumber = 42,
            )

        assertEquals(
            WrkPublishResult.Published(prUrl = "https://github.com/acme/app/pull/12"),
            result,
        )

        // The push landed on the issue-scoped branch, not the session-scoped one.
        val bareShow =
            ProcessBuilder("git", "show", "flow/issue-42:hello.txt")
                .directory(cloneDirectory.toFile())
                .start()
        val shown = bareShow.inputStream.bufferedReader().readText()
        bareShow.waitFor()
        assertTrue(shown.contains("hello from the engine"))

        val body = capturedPrBody ?: error("no PR request captured")
        assertTrue(body.contains("Refs #42"), "expected a Refs reference in: $body")
        assertTrue(!body.contains("Closes #42"), "must not close the linked issue: $body")
      }

  @Test
  fun `an issue body containing a closing keyword is neutralized in the PR body`() = runBlocking {
    val (_, cloneDirectory) = setUpBareRepoAndClone()
    val webClient =
        buildCapturingPrServer(
            responseJson = """{"html_url":"https://github.com/acme/app/pull/9"}"""
        )

    val workspace = fakeWorkspaceWith(fileName = "hello.txt", content = "hi\n")

    val publisher =
        WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

    // The issue's own body carries a closing keyword aimed at a *different* issue.
    publisher.publish(
        repoFullName = "acme/app",
        sessionId = "s8",
        taskHeading = "Follow-up",
        taskMarkdown = "# Follow-up\n\nThis Closes #7 as part of the epic.",
        cloneDirectory = cloneDirectory,
        workspace = workspace,
        issueNumber = 8,
    )

    val body = capturedPrBody ?: error("no PR request captured")
    // The zero-width space is invisible but the raw "Closes #7" adjacency is gone.
    assertTrue(!body.contains("Closes #7"), "closing keyword must be neutralized: $body")
    assertTrue(!WrkClosingKeywords.containsClosingReference(body), "no live closing ref: $body")
    assertTrue(body.contains("Refs #8"))
  }

  @Test
  fun `a manual run's PR body is byte-identical to M1`() = runBlocking {
    val (_, cloneDirectory) = setUpBareRepoAndClone()
    val webClient =
        buildCapturingPrServer(
            responseJson = """{"html_url":"https://github.com/acme/app/pull/3"}"""
        )

    val workspace = fakeWorkspaceWith(fileName = "hello.txt", content = "hi\n")

    val publisher =
        WrkProperGitHubPublisher(gitHubToken = "unused-for-local-remote", webClient = webClient)

    publisher.publish(
        repoFullName = "acme/app",
        sessionId = "s3",
        taskHeading = "Manual",
        taskMarkdown = "# Manual\n\nBody.",
        cloneDirectory = cloneDirectory,
        workspace = workspace,
        issueNumber = null, // the manual path.
    )

    val body = capturedPrBody ?: error("no PR request captured")
    assertEquals("# Manual\n\nBody.\n\n---\nSession: s3", body)
  }

  @Volatile private var capturedPrRequestJson: String? = null

  /** The decoded `body` field of the captured PR-creation request. */
  private val capturedPrBody: String?
    get() = capturedPrRequestJson?.let {
      Json.parseToJsonElement(it).jsonObject.getValue("body").jsonPrimitive.content
    }

  /** Like [buildPrCreationServer] but records the PR request payload for assertions. */
  private fun buildCapturingPrServer(
      responseJson: String,
  ): WebClient {
    server =
        Server.builder()
            .http(0)
            .service("/repos/acme/app/pulls") { _, req ->
              HttpResponse.of(
                  req.aggregate().thenApply { aggregated ->
                    capturedPrRequestJson = aggregated.contentUtf8()
                    HttpResponse.of(HttpStatus.CREATED, MediaType.JSON, responseJson)
                  },
              )
            }
            .build()
            .also { it.start().join() }

    return WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }
}
