package software.medusa.flow.harness.claude

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.time.toJavaDuration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPhysicalTemporaryWorkspace
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectFailureReport
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ProjectHealthStatus
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace
import software.medusa.flow.universal_project.UnpProjectConnection
import software.medusa.flow.universal_project.UnpProjectConnection.JointResult
import software.medusa.flow.universal_project.UnpProjectManifestLoader

/**
 * An [HrsTaskCompleter] that completes a task by driving the `claude` CLI as a subprocess, instead
 * of orchestrating an in-process AI-system graph (the classic engine's approach).
 *
 * **A4 scope — the two run modes.** A3 built the single-shot subprocess driver; A4 adds the
 * manifest probe that chooses between them:
 * - **Manifest-less** (no `project.yaml` in the workspace root — the M4 headline): the prompt is
 *   augmented to tell Claude to discover and run the repo's own checks, `claude` runs **once**, and
 *   there is **no** Flow gate. The repo's own CI is the arbiter.
 * - **Gated** (`project.yaml` present): the manifest drives the same initial bootstrap/analyze/test
 *   health gate as the classic engine (a broken baseline →
 *   [TaskCompletionResult.Failure.JointOperation], with `claude` never run), then after the run a
 *   post-run analyze/test gate. A red gate **bounces**: a fresh `claude --resume <session_id>`
 *   process is spawned with the failing modules' diagnostics as its prompt, up to a small
 *   [bounceBudget]; still red after the budget → [TaskCompletionResult.Failure.AttemptsExhausted]
 *   carrying the final diagnostics.
 *
 * Failure policy follows 01-claude-engine.md: the engine's own operational failures (bad auth, a
 * cap trip, an error result, a crash) are **thrown** as [HrsClaudeEngineException] for the worker
 * to turn into a `failSession` summary. The two health-gate outcomes ([JointOperation],
 * [AttemptsExhausted]) are the only structured `Failure`s — returned, never thrown; the worker
 * publishes nothing for them.
 */
class HrsClaudeTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val projectManifestLoader: UnpProjectManifestLoader,
    private val claudeProcess: HrsClaudeProcess,
    private val config: HrsClaudeEngineConfig,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult {
    observer.observePhase(HrsPipelinePhase.WorkspacePreparing)

    val sourceRootDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity

    val workspace =
        physicalWorkspaceAllocator.allocateWorkspace(templateDirectory = sourceRootDirectory)

    val workspaceRoot =
        (workspace.rootDirectory as? UfsNioDirectory)?.directoryPath
            ?: error(
                "HrsClaudeTaskCompleter requires an on-disk workspace; the allocator returned a " +
                    "${workspace.rootDirectory::class.simpleName} with no filesystem path.",
            )

    // Manifest probe: presence of `project.yaml` in the workspace root selects the run mode.
    // (`UnpProjectManifestLoader.load` throws if it is absent, so we probe the file ourselves
    // rather than catch that exception.)
    val manifestPresent = workspaceRoot.resolve(projectManifestFileName).exists()

    return if (manifestPresent) {
      completeGated(
          workspace = workspace,
          workspaceRoot = workspaceRoot,
          sourceRootDirectory = sourceRootDirectory,
          taskDescription = taskDescription,
          observer = observer,
      )
    } else {
      completeManifestLess(
          workspace = workspace,
          workspaceRoot = workspaceRoot,
          taskDescription = taskDescription,
          observer = observer,
      )
    }
  }

  /**
   * Manifest-less mode: no Flow gate at all. Claude is prompted (with an augmentation instructing
   * it to run the repository's own checks) and run exactly once; a clean result materializes the
   * workspace, any driver failure throws.
   */
  private suspend fun completeManifestLess(
      workspace: PhwWorkspace,
      workspaceRoot: Path,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult {
    observer.observePhase(
        HrsPipelinePhase.ImplementationAttempt(attemptNumber = 1, maxAttempts = 1),
    )

    runClaude(
        invocation =
            invocationFor(
                prompt = manifestLessPrompt(taskDescription),
                workspaceRoot = workspaceRoot,
            ),
        observer = observer,
        runMode = HrsEngineRunMode.ManifestLess,
    )

    return HrsTaskCompleter.TaskCompletionResult.Success(
        temporaryWorkspace = HrsPhysicalTemporaryWorkspace(physicalWorkspace = workspace),
    )
  }

  /**
   * Gated mode: the manifest drives the initial health gate, then the run, then a post-run
   * analyze/test gate with a bounded resume/bounce loop.
   */
  private suspend fun completeGated(
      workspace: PhwWorkspace,
      workspaceRoot: Path,
      sourceRootDirectory: UfsReadonlyDirectory,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult {
    val projectManifest = projectManifestLoader.load(projectDirectory = sourceRootDirectory)
    val projectConnection = projectManifest.connect(physicalWorkspace = workspace)

    observer.observePhase(HrsPipelinePhase.HealthGate)

    checkHealthInitially(projectConnection = projectConnection)?.let { brokenBaseline ->
      // A broken baseline is not the agent's fault — bail before spending a single `claude` run.
      return brokenBaseline
    }

    // Attempt 1: the initial `claude` run against the healthy baseline.
    var attemptNumber = 1
    observer.observePhase(
        HrsPipelinePhase.ImplementationAttempt(
            attemptNumber = attemptNumber,
            maxAttempts = maxImplementationAttempts,
        ),
    )

    var run =
        runClaude(
            invocation =
                invocationFor(
                    prompt = taskDescription.body.render(),
                    workspaceRoot = workspaceRoot,
                ),
            observer = observer,
            runMode = HrsEngineRunMode.Gated,
        )
    var sessionId = run.sessionId

    while (true) {
      observer.observePhase(
          HrsPipelinePhase.HealthCheck(
              attemptNumber = attemptNumber,
              maxAttempts = maxImplementationAttempts,
          ),
      )

      when (val healthStatus = verifySolutionHealth(projectConnection = projectConnection)) {
        ProjectHealthStatus.Healthy ->
            return HrsTaskCompleter.TaskCompletionResult.Success(
                temporaryWorkspace = HrsPhysicalTemporaryWorkspace(physicalWorkspace = workspace),
            )

        is ProjectHealthStatus.Unhealthy -> {
          if (attemptNumber >= maxImplementationAttempts) {
            return HrsTaskCompleter.TaskCompletionResult.Failure.AttemptsExhausted(
                attemptsMade = attemptNumber,
                lastHealthStatus = healthStatus,
            )
          }

          // Bounce: spawn a *fresh* `claude --resume <session_id>` process, feeding the failing
          // modules' diagnostics as the next prompt so it continues the same conversation.
          attemptNumber += 1
          observer.observePhase(
              HrsPipelinePhase.ImplementationAttempt(
                  attemptNumber = attemptNumber,
                  maxAttempts = maxImplementationAttempts,
              ),
          )

          run =
              runClaude(
                  invocation =
                      invocationFor(
                          prompt = bouncePrompt(unhealthy = healthStatus),
                          workspaceRoot = workspaceRoot,
                          resumeSessionId = sessionId,
                      ),
                  observer = observer,
                  runMode = HrsEngineRunMode.Gated,
              )
          run.sessionId?.let { sessionId = it }
        }
      }
    }
  }

  /**
   * Builds an invocation, reusing the tool-policy/env flags and swapping the prompt/resume flags.
   */
  private fun invocationFor(
      prompt: String,
      workspaceRoot: Path,
      resumeSessionId: String? = null,
  ): HrsClaudeInvocation =
      HrsClaudeInvocation(
          arguments = buildArguments(prompt = prompt, resumeSessionId = resumeSessionId),
          environment = config.authEnvironment,
          workingDirectory = workspaceRoot,
      )

  /**
   * The stream-json flags + tool policy + prompt, per 03-cli-notes.md. [resumeSessionId], when set,
   * appends `--resume <id>` so a bounce continues the original session; otherwise this is a fresh
   * run. The tool-policy/env flags are identical either way — only `-p <prompt>` and the resume
   * flag differ between the initial run and a bounce.
   */
  private fun buildArguments(
      prompt: String,
      resumeSessionId: String? = null,
  ): List<String> = buildList {
    add("-p")
    add(prompt)

    add("--output-format")
    add("stream-json")
    add("--verbose")

    add("--setting-sources")
    add(config.toolPolicy.settingSources)

    add("--permission-mode")
    add(config.toolPolicy.permissionMode)

    add("--allowedTools")
    add(config.toolPolicy.allowedTools.joinToString(separator = " "))

    add("--disallowedTools")
    add(config.toolPolicy.disallowedTools.joinToString(separator = " "))

    config.maxBudgetUsd?.let {
      add("--max-budget-usd")
      add(it.toString())
    }

    config.model?.let {
      add("--model")
      add(it)
    }

    config.appendSystemPrompt
        .takeIf { it.isNotBlank() }
        ?.let {
          add("--append-system-prompt")
          add(it)
        }

    resumeSessionId?.let {
      add("--resume")
      add(it)
    }
  }

  /**
   * Runs one `claude` subprocess to completion: emits its banner + terminal cost through the
   * observer and applies the driver-failure verdict (throws on any operational failure). Returns
   * the captured run details (notably the `session_id` needed for a resume bounce).
   */
  private suspend fun runClaude(
      invocation: HrsClaudeInvocation,
      observer: HrsTaskCompleter.Observer,
      runMode: HrsEngineRunMode,
  ): RunOutcome {
    val outcome = runToCompletion(invocation = invocation, observer = observer, runMode = runMode)

    // Surface terminal cost/usage before the verdict: a cap trip or error result still spent money,
    // so the UI should show it even when the run then throws.
    outcome.result?.let { result ->
      observer.observeRunCost(
          HrsRunCost(
              totalCostUsd = result.totalCostUsd,
              numTurns = result.numTurns,
              durationMs = result.durationMs,
          ),
      )
    }

    interpretOutcome(outcome = outcome)

    // No blind spots on success: an otherwise-clean run that still wrote to stderr is worth
    // surfacing, even though it didn't flip the exit code or the result's `is_error` flag.
    outcome.termination.standardError
        .takeIf { it.isNotBlank() }
        ?.let { observer.observeEngineWarning(it) }

    return outcome
  }

  /**
   * Runs the subprocess to completion under the wall-clock cap, collecting the messages we act on.
   */
  private suspend fun runToCompletion(
      invocation: HrsClaudeInvocation,
      observer: HrsTaskCompleter.Observer,
      runMode: HrsEngineRunMode,
  ): RunOutcome {
    val run = claudeProcess.spawn(invocation = invocation)
    try {
      var result: HrsClaudeMessage.Result? = null
      var lastAssistantText: String? = null
      var sessionId: String? = null

      try {
        withTimeout(config.wallClockTimeout.toJavaDuration().toMillis()) {
          run.messages.collect { message ->
            when (message) {
              is HrsClaudeMessage.SystemInit -> {
                sessionId = message.sessionId // A4 uses session_id for resume bounces.
                observer.observeEngineBanner(bannerOf(init = message, runMode = runMode))
              }
              is HrsClaudeMessage.Assistant -> {
                // assistant text → a throttled narrative summary; tool_use → one-line actions.
                summarizeNarrative(message.text)?.let { observer.observeAgentAction(it) }
                message.toolActions.forEach { observer.observeAgentAction(it) }
                if (message.text.isNotBlank()) lastAssistantText = message.text
              }
              is HrsClaudeMessage.Result -> result = message
              is HrsClaudeMessage.Unknown -> Unit
            }
          }
          Unit
        }
      } catch (_: TimeoutCancellationException) {
        val termination = run.awaitTerminationQuietly()
        throw HrsClaudeEngineException.timedOut(
            timeout = config.wallClockTimeout.toString(),
            standardError = termination.standardError,
        )
      }

      val termination = run.awaitTermination()
      return RunOutcome(
          result = result,
          lastAssistantText = lastAssistantText,
          sessionId = sessionId,
          termination = termination,
      )
    } finally {
      run.close()
    }
  }

  /**
   * The initial bootstrap→analyze→test gate, copied from the classic engine: a broken baseline is
   * the operator's problem, not the agent's, so the run is rejected before any `claude` process is
   * spawned.
   */
  private suspend fun checkHealthInitially(
      projectConnection: UnpProjectConnection,
  ): HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation? {
    val bootstrapResult = projectConnection.bootstrapAll()
    if (bootstrapResult is JointResult.Failure) {
      return HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation(
          phase = HrsTaskCompleter.JointOperationPhase.ProjectBootstrapping,
          operationFailure = bootstrapResult,
      )
    }

    // Formatting is mechanical and deterministic: normalize the workspace before the analyze gate
    // checks it, instead of failing the gate over a formatting-only miss.
    projectConnection.normalizeAll()

    val initialAnalyzeResult = projectConnection.analyzeAll()
    if (initialAnalyzeResult is JointResult.Failure) {
      return HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation(
          phase = HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis,
          operationFailure = initialAnalyzeResult,
      )
    }

    val initialTestResult = projectConnection.testAll()
    if (initialTestResult is JointResult.Failure) {
      return HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation(
          phase = HrsTaskCompleter.JointOperationPhase.InitialProjectTesting,
          operationFailure = initialTestResult,
      )
    }

    return null
  }

  /** The post-run analyze+test probe, copied from the classic engine. */
  private suspend fun verifySolutionHealth(
      projectConnection: UnpProjectConnection,
  ): ProjectHealthStatus {
    // See the initial gate's normalizeAll() call: normalize before every analyze, not just once.
    projectConnection.normalizeAll()

    val analyzeResult = projectConnection.analyzeAll()
    if (analyzeResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Analysis,
                  failure = analyzeResult,
              ),
      )
    }

    val testResult = projectConnection.testAll()
    if (testResult is JointResult.Failure) {
      return ProjectHealthStatus.Unhealthy(
          failureReport =
              ProjectFailureReport(
                  stage = ProjectFailureReport.Stage.Testing,
                  failure = testResult,
              ),
      )
    }

    return ProjectHealthStatus.Healthy
  }

  /**
   * Maps a completed run to a verdict: returns cleanly on success, throws on any failure mode.
   *
   * The process outcome is the primary gate: a non-zero exit is a failure **even if the streamed
   * `result` message reported success** — a claude run can write "success" to its payload while the
   * process itself exits non-zero (and/or writes an error to stderr), and that must not sail
   * through as [TaskCompletionResult.Success]. A known subtype (cap trip, auth failure) still
   * labels the failure precisely when one is available; otherwise it's a generic [processFailed].
   */
  private fun interpretOutcome(
      outcome: RunOutcome,
  ) {
    val exitCode = outcome.termination.exitCode
    val standardError = outcome.termination.standardError
    val subtype = outcome.result?.subtype

    if (exitCode != 0) {
      if (isAuthFailure(subtype = subtype, standardError = standardError)) {
        throw HrsClaudeEngineException.authFailure(
            authRung = authRungName(),
            detail = subtype ?: standardError.take(200),
        )
      }
      if (subtype != null && isCapSubtype(subtype)) {
        throw HrsClaudeEngineException.capExceeded(subtype)
      }
      throw HrsClaudeEngineException.processFailed(
          exitCode = exitCode,
          standardError = standardError,
          lastAssistantText = outcome.lastAssistantText,
      )
    }

    val result =
        outcome.result
            ?: throw HrsClaudeEngineException.missingResult(
                exitCode = exitCode,
                standardError = standardError,
            )

    if (!result.isError) return

    when {
      subtype != null && isCapSubtype(subtype) ->
          throw HrsClaudeEngineException.capExceeded(subtype)
      isAuthFailure(subtype = subtype, standardError = standardError) ->
          throw HrsClaudeEngineException.authFailure(
              authRung = authRungName(),
              detail = subtype ?: standardError.take(200),
          )
      else ->
          throw HrsClaudeEngineException.resultError(
              subtype = subtype,
              lastAssistantText = outcome.lastAssistantText,
          )
    }
  }

  private fun authRungName(): String =
      when {
        config.authEnvironment.containsKey("CLAUDE_CODE_OAUTH_TOKEN") -> "personal"
        config.authEnvironment.containsKey("ANTHROPIC_API_KEY") -> "api-key"
        config.authEnvironment.containsKey("CLAUDE_CODE_USE_VERTEX") -> "vertex"
        else -> "personal"
      }

  /** The engine banner for a run's opening `system`/`init` message, tagged with the probed mode. */
  private fun bannerOf(
      init: HrsClaudeMessage.SystemInit,
      runMode: HrsEngineRunMode,
  ): HrsEngineBanner =
      HrsEngineBanner(
          engineName = engineDisplayName,
          cliVersion = config.pinnedCliVersion,
          model = init.model ?: config.model,
          runMode = runMode,
      )

  private data class RunOutcome(
      val result: HrsClaudeMessage.Result?,
      val lastAssistantText: String?,
      val sessionId: String?,
      val termination: HrsClaudeRun.Termination,
  )

  private companion object {
    /** Product-facing name (Anthropic branding: "Claude Agent", never "Claude Code"). */
    const val engineDisplayName = "Claude Agent"

    /**
     * The manifest file whose presence in the workspace root selects gated vs manifest-less mode.
     */
    const val projectManifestFileName = "project.yaml"

    /**
     * How many resume/bounce runs may follow the initial gated run before the gate is declared
     * exhausted. Deliberately small: each bounce is a full `claude` session plus an analyze/test
     * gate, so the cost/latency of one wrong turn compounds fast. The initial run counts as attempt
     * 1, so the total gated `claude` runs are `1 + bounceBudget`.
     */
    const val bounceBudget = 2

    /** Total gated implementation attempts = the initial run plus [bounceBudget] resume bounces. */
    const val maxImplementationAttempts = 1 + bounceBudget

    /** Longest narrative summary emitted per assistant turn; longer text is truncated. */
    const val maxNarrativeLength = 200

    /**
     * The augmentation appended to a manifest-less run's prompt: with no Flow gate, Claude itself
     * must discover and run the repository's own checks before finishing (its CI is the arbiter).
     */
    fun manifestLessPrompt(
        taskDescription: HrsTaskDescription,
    ): String = buildString {
      append(taskDescription.body.render())
      append("\n\n---\n")
      append(
          "This repository has no Flow project manifest, so Flow will run no build, analysis, " +
              "or test gate for you after you finish. Before you stop, you MUST discover and " +
              "run the repository's own checks yourself — inspect its CI configuration and its " +
              "build/lint/test tooling, run those commands, and make sure they pass. The " +
              "repository's own CI is the arbiter of correctness.",
      )
    }

    /**
     * The next prompt for a bounce: the failing modules' per-module diagnostics, plus the
     * fix-and-stop instruction. Fed to a fresh `claude --resume` process.
     */
    fun bouncePrompt(
        unhealthy: ProjectHealthStatus.Unhealthy,
    ): String {
      val diagnostics =
          unhealthy.failureReport.failure.failureByModulePath.entries.joinToString(
              separator = "\n\n",
          ) { (modulePath, moduleFailure) ->
            "${modulePath.toUnixAbsolutePathString()}:\n${moduleFailure.diagnosticOutput}"
          }
      // Must not start with `/`: the bounce prompt is fed to `claude -p …`, and Claude Code
      // interprets a leading `/` on the *first line* as a slash command. The diagnostics begin with
      // the failing module's path, which for a root module is `/` — so an unguarded prompt became
      // `/: …`, was swallowed as an "Unknown command", and the whole diagnostic never reached the
      // model (the bounce loop silently no-op'd). A safe leading line fixes it; later lines are not
      // interpreted as commands.
      return "Diagnostics:\n\n$diagnostics\n\nThe project's checks fail as shown above: fix these " +
          "and stop when the checks pass."
    }

    /**
     * Condenses an assistant text block to a short one-line narrative: the first non-blank line,
     * truncated. Blank text yields `null` (nothing to narrate).
     */
    fun summarizeNarrative(
        text: String,
    ): String? {
      val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
      return firstLine?.let {
        if (it.length <= maxNarrativeLength) it else it.take(maxNarrativeLength).trimEnd() + "…"
      }
    }

    /** Cap trips are surfaced by the CLI as `error_max_*` result subtypes (e.g. budget). */
    fun isCapSubtype(
        subtype: String,
    ): Boolean = subtype.startsWith("error_max_")

    private val AUTH_MARKERS =
        listOf(
            "authentication",
            "unauthorized",
            "oauth",
            "credit balance",
            "401",
            "invalid api key",
        )

    fun isAuthFailure(
        subtype: String?,
        standardError: String,
    ): Boolean {
      val haystack = ((subtype ?: "") + "\n" + standardError).lowercase()
      return AUTH_MARKERS.any { haystack.contains(it) }
    }

    suspend fun HrsClaudeRun.awaitTerminationQuietly(): HrsClaudeRun.Termination =
        try {
          awaitTermination()
        } catch (_: Exception) {
          HrsClaudeRun.Termination(exitCode = -1, standardError = "")
        }
  }
}
