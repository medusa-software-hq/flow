package software.medusa.flow.harness.claude

import kotlin.time.toJavaDuration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.flow.harness.HrsEngineBanner
import software.medusa.flow.harness.HrsEngineRunMode
import software.medusa.flow.harness.HrsPhysicalTemporaryWorkspace
import software.medusa.flow.harness.HrsPipelinePhase
import software.medusa.flow.harness.HrsRunCost
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.allocateWorkspace

/**
 * An [HrsTaskCompleter] that completes a task by driving the `claude` CLI as a subprocess, instead
 * of orchestrating an in-process AI-system graph (the classic engine's approach).
 *
 * **A3 scope — the driver only.** It allocates a physical workspace, runs `claude` against it
 * exactly once, and maps the terminal result. The manifest health gate, the analyze/test probe, and
 * the resume/bounce loop are A4; wiring this into the CLI is A6. So this class is built and unit-
 * tested but is not yet selected by any worker.
 *
 * Failure policy follows 01-claude-engine.md: the engine's own operational failures (bad auth, a
 * cap trip, an error result, a crash) are **thrown** as [HrsClaudeEngineException] for the worker
 * to turn into a `failSession` summary — never returned as a structured `Failure`, whose subtypes
 * are A4's health-gate concepts.
 */
class HrsClaudeTaskCompleter(
    private val physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    private val claudeProcess: HrsClaudeProcess,
    private val config: HrsClaudeEngineConfig,
) : HrsTaskCompleter {
  override suspend fun completeTask(
      sourceGitWorktree: GitWorktree,
      taskDescription: HrsTaskDescription,
      observer: HrsTaskCompleter.Observer,
  ): HrsTaskCompleter.TaskCompletionResult {
    observer.observePhase(HrsPipelinePhase.WorkspacePreparing)

    val workspace =
        physicalWorkspaceAllocator.allocateWorkspace(
            templateDirectory = sourceGitWorktree.rootDirectory.asFilteredFilesystemEntity,
        )

    // A3 runs the engine as a single implementation attempt; the bounce loop that would make this
    // (n of N) is A4.
    observer.observePhase(
        HrsPipelinePhase.ImplementationAttempt(attemptNumber = 1, maxAttempts = 1)
    )

    val workspaceRoot =
        (workspace.rootDirectory as? UfsNioDirectory)?.directoryPath
            ?: error(
                "HrsClaudeTaskCompleter requires an on-disk workspace; the allocator returned a " +
                    "${workspace.rootDirectory::class.simpleName} with no filesystem path.",
            )

    val invocation =
        HrsClaudeInvocation(
            arguments = buildArguments(taskDescription = taskDescription),
            environment = config.authEnvironment,
            workingDirectory = workspaceRoot,
        )

    val outcome = runToCompletion(invocation = invocation, observer = observer)

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

    return HrsTaskCompleter.TaskCompletionResult.Success(
        temporaryWorkspace = HrsPhysicalTemporaryWorkspace(physicalWorkspace = workspace),
    )
  }

  /** The stream-json flags + tool policy + prompt, per 03-cli-notes.md. */
  private fun buildArguments(
      taskDescription: HrsTaskDescription,
  ): List<String> = buildList {
    add("-p")
    add(taskDescription.body.render())

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
  }

  /**
   * Runs the subprocess to completion under the wall-clock cap, collecting the messages we act on.
   */
  private suspend fun runToCompletion(
      invocation: HrsClaudeInvocation,
      observer: HrsTaskCompleter.Observer,
  ): RunOutcome {
    val run = claudeProcess.spawn(invocation = invocation)
    try {
      var result: HrsClaudeMessage.Result? = null
      var lastAssistantText: String? = null

      try {
        withTimeout(config.wallClockTimeout.toJavaDuration().toMillis()) {
          run.messages.collect { message ->
            when (message) {
              is HrsClaudeMessage.SystemInit ->
                  observer.observeEngineBanner(bannerOf(message)) // A4 uses session_id for resume
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
          termination = termination,
      )
    } finally {
      run.close()
    }
  }

  /** Maps a completed run to a verdict: returns cleanly on success, throws on any failure mode. */
  private fun interpretOutcome(
      outcome: RunOutcome,
  ) {
    val result =
        outcome.result
            ?: throw HrsClaudeEngineException.missingResult(
                exitCode = outcome.termination.exitCode,
                standardError = outcome.termination.standardError,
            )

    if (!result.isError) return

    val subtype = result.subtype
    when {
      subtype != null && isCapSubtype(subtype) ->
          throw HrsClaudeEngineException.capExceeded(subtype)
      isAuthFailure(subtype = subtype, standardError = outcome.termination.standardError) ->
          throw HrsClaudeEngineException.authFailure(
              authRung = authRungName(),
              detail = subtype ?: outcome.termination.standardError.take(200),
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

  /** The engine banner for a run's opening `system`/`init` message. */
  private fun bannerOf(
      init: HrsClaudeMessage.SystemInit,
  ): HrsEngineBanner =
      HrsEngineBanner(
          engineName = engineDisplayName,
          cliVersion = config.pinnedCliVersion,
          model = init.model ?: config.model,
          // A3 has no manifest health gate — the run is always manifest-less; A4 flips this to
          // Gated
          // when a manifest drives an analyze/test gate.
          runMode = HrsEngineRunMode.ManifestLess,
      )

  private data class RunOutcome(
      val result: HrsClaudeMessage.Result?,
      val lastAssistantText: String?,
      val termination: HrsClaudeRun.Termination,
  )

  private companion object {
    /** Product-facing name (Anthropic branding: "Claude Agent", never "Claude Code"). */
    const val engineDisplayName = "Claude Agent"

    /** Longest narrative summary emitted per assistant turn; longer text is truncated. */
    const val maxNarrativeLength = 200

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
