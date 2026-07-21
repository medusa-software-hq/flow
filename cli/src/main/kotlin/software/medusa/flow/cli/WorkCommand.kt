package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.mordant.terminal.Terminal
import com.linecorp.armeria.client.WebClient
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubapp.GitHubAppTokenMinter
import software.medusa.flow.githubapp.RefreshingGitHubAppToken
import software.medusa.flow.worker.WrkConfig
import software.medusa.flow.worker.WrkEngineResolver
import software.medusa.flow.worker.WrkGitHubTokenSupplierFactory
import software.medusa.flow.worker.WrkGrpcApiClient
import software.medusa.flow.worker.WrkPollLoop
import software.medusa.flow.worker.WrkProcessGitCloner
import software.medusa.flow.worker.WrkProperGitHubPublisher
import software.medusa.flow.worker.WrkProperSessionProcessor
import software.medusa.flow.worker.WrkRegistrationLoop
import software.medusa.flow.worker.WrkWorkerIdentity

class WorkCommand(
    private val terminal: Terminal,
    // Lazily built so a user running a read command (`flow sessions`) never constructs the engine
    // composition or needs model credentials — only `work` (the container entrypoint) does.
    private val engineResolverProvider: () -> WrkEngineResolver,
) : CliktCommand(name = "work") {
  override fun run() {
    val config = WrkConfig.fromEnvironment()

    // Build the engine composition now that we know `work` is actually running.
    val engineResolver = engineResolverProvider()

    // Workers are uniform (every worker runs every engine), so the claim carries no capability set.
    val apiClient = WrkGrpcApiClient.create(apiUrl = config.apiUrl)

    // Startup breadcrumb: the API URL doubles as the ID-token audience, so this line makes the
    // worker's identity target visible in its own logs — the first thing to check when a hosted
    // worker isn't claiming (is it even pointed at the right API / audience?).
    terminal.println("Worker starting: audience=${config.apiUrl}; polling for sessions")

    // Test-only, and inert in production: a faster heartbeat lets the sad-path tests reach lazy
    // session expiry in seconds instead of minutes. Unset → the production default.
    val heartbeatIntervalMillis =
        System.getenv("FLOW_WORKER_HEARTBEAT_INTERVAL_MILLIS")?.toLong()
            ?: WrkProperSessionProcessor.defaultHeartbeatIntervalMillis

    // Test-only, and hard-gated behind the same flag as the scripted engine: the "crash
    // mid-publish"
    // sad path halts the process after the push but before CompleteSession, so the control plane
    // never learns the work landed. `halt` (not exit) skips shutdown hooks — a real crash.
    val crashAfterPublish =
        System.getenv("FLOW_ALLOW_SCRIPTED_ENGINE") == "1" &&
            System.getenv("FLOW_SCRIPTED_ENGINE_BEHAVIOR") == "crash-after-publish"

    val beforeCompleteSession: suspend () -> Unit = {
      if (crashAfterPublish) {
        terminal.println("Scripted crash: halting after publish, before CompleteSession")
        Runtime.getRuntime().halt(137)
      }
    }

    // The worker mints its own short-lived GitHub App installation token per session (refreshing it
    // while the session runs) rather than carrying a static token. One WebClient serves both the
    // token mint and the PR-create call; FLOW_GITHUB_API_BASE_URL (test-only) points them at the
    // local GitHub stub, otherwise the real GitHub API. Git clone/push is redirected separately via
    // git's `insteadOf` (env), so it needs no base-URL override.
    val gitHubWebClient =
        when (val baseUrl = System.getenv("FLOW_GITHUB_API_BASE_URL")) {
          null -> WebClient.of(GitHubAppTokenMinter.GITHUB_API_BASE_URL)
          else -> WebClient.of(baseUrl)
        }
    val gitHubTokenSupplierFactory = WrkGitHubTokenSupplierFactory { repoFullName ->
      val (owner, name) = repoFullName.split("/", limit = 2)
      val refreshing =
          RefreshingGitHubAppToken(
              GitHubAppTokenMinter(
                  GitHubAppConfig(
                      clientId = config.githubAppClientId,
                      pemContent = config.githubAppPemContent,
                      repoOwner = owner,
                      repoName = name,
                  ),
                  gitHubWebClient,
              ),
          )
      refreshing::current
    }

    val publisher =
        WrkProperGitHubPublisher(
            tokenSupplierFactory = gitHubTokenSupplierFactory,
            webClient = gitHubWebClient,
        )

    val sessionProcessor =
        WrkProperSessionProcessor(
            gitCloner = WrkProcessGitCloner(tokenSupplierFactory = gitHubTokenSupplierFactory),
            engineResolver = engineResolver,
            publisher = publisher,
            log = { terminal.println(it) },
            beforeCompleteSession = beforeCompleteSession,
            heartbeatIntervalMillis = heartbeatIntervalMillis,
        )

    val pollLoop =
        WrkPollLoop(
            apiClient = apiClient,
            sessionProcessor = sessionProcessor,
            log = { terminal.println(it) },
        )

    // Fleet registration (M5): a background loop that keeps this worker's registry entry fresh so
    // the system-test gate can see it's alive and what build it runs — independent of, and
    // concurrent with, the poll loop, so a long session never makes the worker look "down".
    val registrationLoop =
        WrkRegistrationLoop(
            apiClient = apiClient,
            identity = WrkWorkerIdentity.fromEnvironment(),
            log = { terminal.println(it) },
        )

    runBlocking {
      val registrationJob = launch { registrationLoop.run() }
      val loopJob = launch { pollLoop.run() }

      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                terminal.println("Shutting down, waiting for the current session to finish...")
                runBlocking {
                  registrationJob.cancelAndJoin()
                  loopJob.cancelAndJoin()
                }
              },
          )

      loopJob.join()
      registrationJob.cancelAndJoin()
    }
  }
}
