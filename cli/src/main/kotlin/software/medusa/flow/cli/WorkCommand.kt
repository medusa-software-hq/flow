package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.mordant.terminal.Terminal
import com.linecorp.armeria.client.WebClient
import java.time.Duration
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import software.medusa.flow.githubapp.GitHubAppConfig
import software.medusa.flow.githubapp.GitHubAppTokenMinter
import software.medusa.flow.githubapp.RefreshingGitHubAppToken
import software.medusa.flow.worker.WrkAuthWedgeWatchdog
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

    // Signing is opt-in (FLOW_ENABLE_GPG_SIGNING); when on, the key must be present or the worker
    // can't produce the signed commits the branch ruleset requires — fail fast rather than push
    // unsigned commits that GitHub will reject.
    val gpgPrivateKey =
        if (config.gpgSigningEnabled) {
          config.gpgPrivateKey
              ?: error("FLOW_ENABLE_GPG_SIGNING is true but FLOW_WORKER_GPG_PRIVATE_KEY is not set")
        } else {
          null
        }

    val publisher =
        WrkProperGitHubPublisher(
            tokenSupplierFactory = gitHubTokenSupplierFactory,
            webClient = gitHubWebClient,
            authorEmail = config.authorEmail,
            gpgPrivateKey = gpgPrivateKey,
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

    // The poll and registration loops share one identity token (WrkGrpcApiClient), so an
    // UNAUTHENTICATED wedge shows up on both — either can trip this first. Both loops already force
    // a fresh token on every UNAUTHENTICATED; if that isn't clearing it within the threshold, the
    // underlying identity source itself is stuck, not just the cache, and retrying forever would
    // silently blackhole this worker until a human notices and restarts it. Halting instead lets
    // the supervisor respawn with a clean process. Configurable for ops tuning; unset uses 10min,
    // comfortably past any transient control-plane blip but well short of the multi-hour wedges
    // this exists for.
    val authWedgeThresholdMillis =
        System.getenv("FLOW_WORKER_AUTH_WEDGE_THRESHOLD_MILLIS")?.toLong()
            ?: Duration.ofMinutes(10).toMillis()
    val authWedgeWatchdog =
        WrkAuthWedgeWatchdog(
            threshold = Duration.ofMillis(authWedgeThresholdMillis),
            onWedged = { elapsed ->
              terminal.println(
                  "FATAL: auth wedged for ${elapsed.toMinutes()}min (UNAUTHENTICATED since first " +
                      "failure, forced credential refresh didn't clear it); halting so the " +
                      "supervisor restarts us",
              )
              Runtime.getRuntime().halt(1)
            },
        )

    val pollLoop =
        WrkPollLoop(
            apiClient = apiClient,
            sessionProcessor = sessionProcessor,
            authWedgeWatchdog = authWedgeWatchdog,
            log = { terminal.println(it) },
        )

    // Fleet registration (M5): a background loop that keeps this worker's registry entry fresh so
    // the system-test gate can see it's alive and what build it runs — independent of, and
    // concurrent with, the poll loop, so a long session never makes the worker look "down".
    val registrationLoop =
        WrkRegistrationLoop(
            apiClient = apiClient,
            identity = WrkWorkerIdentity.fromEnvironment(),
            authWedgeWatchdog = authWedgeWatchdog,
            log = { terminal.println(it) },
        )

    // The supervisor's `docker stop -t <D>` timeout, mirrored here so the worker can act *before*
    // the external SIGKILL lands: past this point it force-kills the in-flight session itself and
    // tags it FAILED with an explicit reason, rather than being silently SIGKILLed with the session
    // left to expire lazily via heartbeat timeout. Unset (the default) means drain has no
    // self-imposed bound — it waits for the session to finish, however long that takes.
    val drainDeadlineMillis = System.getenv("FLOW_WORKER_DRAIN_DEADLINE_MILLIS")?.toLong()

    runBlocking {
      val registrationJob = launch { registrationLoop.run() }
      val loopJob = launch { pollLoop.run() }

      // SIGTERM means drain, not abort: cordon (stop claiming new sessions) and let whatever is
      // already in flight run to completion, then exit 0. An idle worker has nothing in flight, so
      // this returns immediately. The JVM doesn't consider shutdown complete — and won't exit —
      // until this hook returns, which is exactly what gives the in-flight session room to finish.
      Runtime.getRuntime()
          .addShutdownHook(
              Thread {
                terminal.println(
                    "Draining: no new sessions will be claimed; waiting for the in-flight " +
                        "session (if any) to finish",
                )
                pollLoop.cordon()
                runBlocking {
                  val drainedInTime =
                      if (drainDeadlineMillis == null) {
                        loopJob.join()
                        true
                      } else {
                        withTimeoutOrNull(drainDeadlineMillis) { loopJob.join() } != null
                      }

                  if (!drainedInTime) {
                    val inFlight = pollLoop.inFlightSessionIds()
                    terminal.println(
                        "Drain deadline (${drainDeadlineMillis}ms) exceeded with " +
                            "${inFlight.size} session(s) still running; force-failing and exiting",
                    )
                    pollLoop.forceFailInFlight(reason = drainDeadlineFailureReason)
                    loopJob.cancelAndJoin()
                  }

                  registrationJob.cancelAndJoin()
                }
              },
          )

      loopJob.join()
      registrationJob.cancelAndJoin()
    }
  }

  companion object {
    /**
     * Marker substring for a session force-failed because the worker hit its drain deadline —
     * distinct from generic heartbeat-timeout expiry, so the affected issue is trivially
     * identifiable from the failure summary alone.
     */
    const val drainDeadlineFailureReason =
        "Worker was replaced while this session was still running " +
            "(worker_replaced_at_drain_deadline)"
  }
}
