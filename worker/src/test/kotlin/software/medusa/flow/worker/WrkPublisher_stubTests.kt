package software.medusa.flow.worker

import com.linecorp.armeria.client.WebClient
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.githubstub.BareRepoFixture
import software.medusa.flow.githubstub.FakeGitHubServer
import software.medusa.flow.harness.HrsReadonlyTemporaryWorkspace

/**
 * Story-01 acceptance: the *real* [WrkProperGitHubPublisher] runs a clone → push → PR-create cycle
 * against a [BareRepoFixture] remote and [FakeGitHubServer] — the git+GitHub seam previously only
 * covered by an ad-hoc test server.
 */
class WrkPublisher_stubTests {
  private val stub = FakeGitHubServer().start()

  @AfterTest fun tearDown() = stub.close()

  private class FakeWorkspace(
      override val rootDirectory: UfsReadonlyDirectory,
  ) : HrsReadonlyTemporaryWorkspace {
    override fun close() = Unit
  }

  private fun runGit(
      dir: Path,
      vararg args: String,
  ) {
    val p =
        ProcessBuilder(listOf("git", *args))
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
    val out = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
  }

  @Test
  fun `publish clones the bare remote, pushes a branch, and opens a PR in the stub`() =
      runBlocking {
        val tmp = Files.createTempDirectory("wrk-stub-")
        // Seed a bare "GitHub" remote from a trivial project.
        val seed = Files.createTempDirectory(tmp, "seed-")
        seed.resolve("README.md").toFile().writeText("hello\n")
        val remote = BareRepoFixture.create(parentDirectory = tmp, seedDirectory = seed)
        stub.seedRepo("acme/app", remotePath = remote.remotePath.toString())

        // The worker operates on a clone of that remote.
        val clone = Files.createTempDirectory(tmp, "clone-")
        runGit(tmp, "clone", "-q", remote.remotePath.toString(), clone.toString())

        // Engine output: a new file to publish.
        val wsDir = UfsNioDirectory.createTemporary(prefix = UfsName.Literal("wrk-stub-ws-"))
        wsDir.createFile(
            name = UfsName.Literal("greeting.txt"),
            initialContent = ByteString("hi\n".toByteArray()),
        )

        val result =
            WrkProperGitHubPublisher(
                    tokenSupplierFactory =
                        WrkGitHubTokenSupplierFactory { { "unused-for-local-remote" } },
                    webClient = WebClient.of(stub.baseUrl),
                )
                .publish(
                    repoFullName = "acme/app",
                    sessionId = "s1",
                    taskHeading = "Add greeting",
                    taskMarkdown = "# Add greeting",
                    cloneDirectory = clone,
                    workspace = FakeWorkspace(wsDir),
                    issueNumber = null,
                )

        val published = assertIs<WrkPublishResult.Published>(result)
        // PR was created in the stub.
        val pr = stub.openPullRequest("acme/app")
        assertTrue(
            pr != null && published.prUrl.endsWith("/pull/${pr.number}"),
            "PR opened in stub: ${published.prUrl}",
        )
        assertEquals("flow/session-s1", pr!!.head)
        // The branch actually landed on the bare remote with the engine's file.
        assertTrue(remote.hasBranch("flow/session-s1"))
        assertTrue(remote.showFile("greeting.txt", "flow/session-s1").contains("hi"))
      }
}
