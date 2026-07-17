package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.mordant.terminal.Terminal
import com.linecorp.armeria.client.WebClient
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.worker.WrkConfig
import software.medusa.flow.worker.WrkGrpcApiClient
import software.medusa.flow.worker.WrkPollLoop
import software.medusa.flow.worker.WrkProcessGitCloner
import software.medusa.flow.worker.WrkProperGitHubPublisher
import software.medusa.flow.worker.WrkProperSessionProcessor

class WorkCommand(
    private val terminal: Terminal,
    private val taskCompleter: HrsTaskCompleter,
) : CliktCommand(name = "work") {
  override fun run() {
    val config = WrkConfig.fromEnvironment()

    val apiClient = WrkGrpcApiClient.create(apiUrl = config.apiUrl)

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

    // Test-only: point the PR-creation HTTP at a local GitHub stub (the hermetic loop test). Unset
    // in production → the publisher uses the real GitHub API base URL. Git clone/push is redirected
    // separately via git's `insteadOf` (env), so no override is needed here for that.
    val publisher =
        when (val baseUrl = System.getenv("FLOW_GITHUB_API_BASE_URL")) {
          null -> WrkProperGitHubPublisher(gitHubToken = config.workerGitHubToken)
          else ->
              WrkProperGitHubPublisher(
                  gitHubToken = config.workerGitHubToken,
                  webClient = WebClient.of(baseUrl),
              )
        }

    val sessionProcessor =
        WrkProperSessionProcessor(
            gitCloner = WrkProcessGitCloner(gitHubToken = config.workerGitHubToken),
            taskCompleter = taskCompleter,
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

    runBlocking {
      val loopJob = launch { pollLoop.run() }

      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                terminal.println("Shutting down, waiting for the current session to finish...")
                runBlocking { loopJob.cancelAndJoin() }
              },
          )

      loopJob.join()
    }
  }
}
