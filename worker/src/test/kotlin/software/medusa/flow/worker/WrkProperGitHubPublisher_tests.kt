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
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace

private fun runGit(
    directory: Path,
    vararg args: String,
) {
  val exitCode =
      ProcessBuilder(listOf("git", "-c", "commit.gpgsign=false") + args)
          .directory(directory.toFile())
          .redirectErrorStream(true)
          .start()
          .also { it.inputStream.readAllBytes() }
          .waitFor()

  check(exitCode == 0) { "git ${args.joinToString(" ")} failed in $directory" }
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
    runGit(bareDirectory, "init", "--bare", "-q")

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
        )

    assertEquals(WrkPublishResult.NoChanges, result)

    // Nothing was ever pushed to the "GitHub" (bare) side -- only the local clone got the branch.
    val branchList =
        ProcessBuilder("git", "branch", "--list").directory(bareDirectory.toFile()).start()
    val output = branchList.inputStream.bufferedReader().readText()
    branchList.waitFor()
    assertTrue(!output.contains("flow/session-s2"))
  }
}
