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
