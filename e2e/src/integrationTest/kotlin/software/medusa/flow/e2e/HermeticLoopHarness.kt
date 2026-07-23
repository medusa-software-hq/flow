package software.medusa.flow.e2e

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.server.Server
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubstub.BareRepoFixture
import software.medusa.flow.githubstub.FakeGitHubAppKey
import software.medusa.flow.githubstub.FakeGitHubServer
import software.medusa.flow.server.GitHubAppCandidateClient
import software.medusa.flow.server.GitHubAppClient
import software.medusa.flow.server.GitHubAppIssueClient
import software.medusa.flow.server.GitHubAppPrClient
import software.medusa.flow.server.InMemoryGithubOutboxStore
import software.medusa.flow.server.InMemoryIssuePipelineStore
import software.medusa.flow.server.InMemoryPipelineBackend
import software.medusa.flow.server.InMemorySessionStore
import software.medusa.flow.server.NoOpAuthDecorator
import software.medusa.flow.server.WorkerAuthorizer
import software.medusa.flow.server.buildServer
import software.medusa.flow.v1.ReconcileServiceGrpcKt
import software.medusa.flow.v1.reconcileRequest

/**
 * Boots every leg of the loop except GitHub: the real control plane in-process with the *real*
 * GitHub clients pointed at [FakeGitHubServer], a real bare git repo standing in for the remote,
 * and the *shipped* worker binary as a subprocess.
 *
 * Only three things diverge from production, and each is forced rather than convenient: cheap
 * models (cost), the GitHub base URL (GitHub is a stub), and no ID token (the control plane is
 * local). Everything else — packaging, wiring, engine, git, the loop — is the real thing.
 */
class HermeticLoopHarness
private constructor(
    val fixture: LoopFixture,
    val stub: FakeGitHubServer,
    val bareRepo: BareRepoFixture,
    val sessions: InMemorySessionStore,
    val pipelines: InMemoryIssuePipelineStore,
    private val server: Server,
) : AutoCloseable {
  companion object {
    const val repoFullName = "acme/app"
    const val repoOwner = "acme"
    const val issueNumber = 1

    /** Boots the stub, the bare remote and the control plane. The worker starts separately. */
    fun start(
        fixture: LoopFixture,
        workDirectory: Path,
        seedDirectory: Path,
        // Shortened by the sad-path tests so a lost/hung worker's session expires in seconds, not
        // the production 3 minutes. The hermetic loop leaves it at the default.
        heartbeatTimeout: Duration = InMemorySessionStore.defaultHeartbeatTimeout,
    ): HermeticLoopHarness {
      val stub = FakeGitHubServer().start()

      val bareRepo =
          BareRepoFixture.create(parentDirectory = workDirectory, seedDirectory = seedDirectory)

      stub.seedRepo(repoFullName, remotePath = bareRepo.remotePath.toString())

      // The real App client, doing the real JWT → installation-token dance against the stub.
      val appClient =
          GitHubAppClient(
              GitHubAppConfig(
                  clientId = "loop-test-client-id",
                  pemContent = FakeGitHubAppKey.pkcs8Pem,
                  repoOwner = repoOwner,
                  repoName = "app",
              ),
              webClient = WebClient.of(stub.baseUrl),
          )

      val backend = InMemoryPipelineBackend()
      val pipelines = InMemoryIssuePipelineStore(backend)
      val sessions = InMemorySessionStore(heartbeatTimeout = heartbeatTimeout)

      val server =
          buildServer(
                  originRegex = ".*",
                  port = 0,
                  // The worker sends no ID token (there are no Google credentials here), so the
                  // control plane must not demand an identity. Authorization is still server-side:
                  // this is the local-server posture, not a bypass of a real one.
                  auth = NoOpAuthDecorator,
                  gitHubIssueStore = software.medusa.flow.server.FakeGitHubIssueStore(),
                  gitHubRepositoryStore = software.medusa.flow.server.FakeGitHubRepositoryStore(),
                  sessionStore = sessions,
                  workerAuthorizer = WorkerAuthorizer.permissive,
                  issuePipelineStore = pipelines,
                  githubOutboxStore = InMemoryGithubOutboxStore(backend),
                  // Real clients over real HTTP — only the base URL is the stub.
                  gitHubIssueClient = GitHubAppIssueClient(appClient),
                  gitHubPrClient = GitHubAppPrClient(appClient),
                  gitHubCandidateClient = GitHubAppCandidateClient(appClient),
                  reconcileAuthorizer = WorkerAuthorizer.permissive,
              )
              .also { it.start().join() }

      return HermeticLoopHarness(
          fixture = fixture,
          stub = stub,
          bareRepo = bareRepo,
          sessions = sessions,
          pipelines = pipelines,
          server = server,
      )
    }
  }

  val apiUrl: String
    get() = "http://127.0.0.1:${server.activeLocalPort()}"

  private val reconcileClient by lazy {
    GrpcClients.newClient(
        "$apiUrl/",
        ReconcileServiceGrpcKt.ReconcileServiceCoroutineStub::class.java,
    )
  }

  /** One reconcile pass over the stub's repo, through the real RPC surface. */
  suspend fun reconcile() {
    reconcileClient.reconcile(reconcileRequest { repoFullName = HermeticLoopHarness.repoFullName })
  }

  /**
   * Reconciles repeatedly (bounded) until [done] — the reconciler drains one outbox entry per pass.
   */
  suspend fun reconcileUntil(
      what: String,
      maxPasses: Int = 15,
      done: () -> Boolean,
  ) {
    repeat(maxPasses) {
      if (done()) return
      reconcile()
      delay(200)
    }
    check(done()) { "$what: not reached after $maxPasses reconcile passes" }
  }

  /**
   * Launches the shipped fat jar as `flow work`, exactly as a real worker runs — the artifact under
   * test, not a re-wired composition root. The **real** (AI) builtin engine; needs
   * `OPENROUTER_API_KEY`.
   *
   * Dual-engine fan-out makes every pipeline's *primary* session Claude, so `FLOW_TEST_CLAUDE_AS_-
   * BUILTIN` routes that primary to the builtin engine here — the full loop stays cheap and needs
   * no real `claude`. (The builtin shadow session also runs, on its own `-builtin` branch.)
   */
  fun startWorker(): WorkerProcess =
      startWorkerProcess(
          extraEnv =
              mapOf(
                  // The one credential that is genuinely real. Budget-capped and engine-test
                  // scoped.
                  "OPENROUTER_API_KEY" to
                      checkNotNull(System.getenv("OPENROUTER_API_KEY")) {
                        "OPENROUTER_API_KEY is not set — the loop test needs a real model"
                      },
                  "FLOW_TEST_CHEAP_MODELS" to "1",
                  "FLOW_TEST_CLAUDE_AS_BUILTIN" to "1",
              ),
      )

  /**
   * Launches the shipped binary for a claude-engine run — the real `claude` CLI, in gated mode (the
   * `gradle` fixture ships a `project.yaml`, so the engine runs the real gradle analyze+test gate).
   * Under fan-out the pipeline's *primary* session is always Claude, so this worker runs that
   * primary on the real `claude` binary (unlike [startWorker], it does not route Claude to
   * builtin); it only sets the auth rung `FLOW_CLAUDE_AUTH=personal` so the CLI authenticates with
   * the operator's subscription token.
   *
   * Everything the CLI itself needs — `CLAUDE_CODE_OAUTH_TOKEN`, `PATH`, `HOME`, and the `claude`
   * binary on PATH — arrives by env inheritance from the CI step ([startWorkerProcess] inherits the
   * parent env then layers `extraEnv` on top), so this method sets none of them; it only names the
   * auth rung and an optional model override. Requires `CLAUDE_CODE_OAUTH_TOKEN` set and `claude`
   * on PATH in the environment that runs the test.
   */
  fun startClaudeWorker(): WorkerProcess =
      startWorkerProcess(
          extraEnv =
              mapOf("FLOW_CLAUDE_AUTH" to "personal") +
                  (System.getenv("FLOW_CLAUDE_MODEL")?.let { mapOf("FLOW_CLAUDE_MODEL" to it) }
                      ?: emptyMap()),
      )

  /**
   * Launches the shipped binary with the **scripted** engine ([behavior]) — deterministic, no
   * model, so the sad-path suite needs no API key. [heartbeatIntervalMillis] is shortened so a hung
   * worker still heartbeats faster than the harness's (also shortened) expiry timeout.
   */
  fun startScriptedWorker(
      behavior: String,
      heartbeatIntervalMillis: Long,
  ): WorkerProcess =
      startWorkerProcess(
          extraEnv =
              mapOf(
                  "FLOW_WORKER_ENGINE" to "scripted",
                  "FLOW_ALLOW_SCRIPTED_ENGINE" to "1",
                  "FLOW_SCRIPTED_ENGINE_BEHAVIOR" to behavior,
                  "FLOW_WORKER_HEARTBEAT_INTERVAL_MILLIS" to heartbeatIntervalMillis.toString(),
              ),
      )

  private fun startWorkerProcess(
      extraEnv: Map<String, String>,
  ): WorkerProcess {
    val cliJar =
        checkNotNull(System.getProperty("flow.cli.jar")) {
          "flow.cli.jar system property is not set (see e2e/build.gradle.kts)"
        }

    val builder =
        ProcessBuilder(
                "java",
                // The application plugin passes this; running the jar directly must too (JNA).
                "--enable-native-access=ALL-UNNAMED",
                "-jar",
                cliJar,
                "work",
            )
            .redirectErrorStream(true)

    builder.environment().apply {
      put("FLOW_API_URL", apiUrl)
      // The worker mints its own GitHub App installation token against the stub (which serves the
      // App endpoints), exactly as it would against real GitHub — no static token.
      put("FLOW_WORKER_GITHUB_APP_CLIENT_ID", "loop-test-client-id")
      put("FLOW_WORKER_GITHUB_APP_PEM", FakeGitHubAppKey.pkcs8Pem)

      // Forced divergences shared by both engines: GitHub is a stub, and the control plane is local
      // (no ID token).
      put("FLOW_GITHUB_API_BASE_URL", stub.baseUrl)
      put("FLOW_TEST_SKIP_API_AUTH", "1")

      // Send the worker's clone/push at the bare repo instead of github.com. The worker builds
      // exactly this URL (WrkProcessGitCloner), and git applies insteadOf to pushes too, so the
      // `origin` it clones from is rewritten in both directions without the worker knowing.
      put("GIT_CONFIG_COUNT", "1")
      put("GIT_CONFIG_KEY_0", "url.${bareRepo.remoteUrl}.insteadOf")
      put("GIT_CONFIG_VALUE_0", "https://x-access-token@github.com/$repoFullName.git")

      putAll(extraEnv)
    }

    return WorkerProcess(builder.start())
  }

  override fun close() {
    runCatching { server.stop().join() }
    runCatching { stub.close() }
  }
}

/** The worker subprocess, with its output retained so a failure can say what it was doing. */
class WorkerProcess(
    private val process: Process,
) : AutoCloseable {
  private val output = StringBuilder()

  private val pump =
      Thread {
            runCatching {
              process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(output) { output.appendLine(line) }
              }
            }
          }
          .apply {
            isDaemon = true
            start()
          }

  /** Everything the worker has printed so far — the first thing to read when the loop fails. */
  fun output(): String = synchronized(output) { output.toString() }

  val isAlive: Boolean
    get() = process.isAlive

  /** The exit code if the worker has already died, else null (it should outlive the assertions). */
  fun exitCodeOrNull(): Int? = if (process.isAlive) null else process.exitValue()

  /** SIGKILL the worker immediately (the "kill the worker and watch" sad path). */
  fun kill() {
    process.destroyForcibly()
  }

  override fun close() {
    process.destroy()
    if (!process.waitFor(20, TimeUnit.SECONDS)) process.destroyForcibly()
    pump.interrupt()
  }
}
