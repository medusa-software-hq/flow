package software.medusa.flow.harness.claude

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Static configuration for [HrsClaudeTaskCompleter] — everything that varies per worker/deployment
 * rather than per task.
 *
 * @property authEnvironment the auth vars for the active rung, injected verbatim into the (replace-
 *   not-inherit) subprocess environment. For the default **personal** rung this is a single
 *   `CLAUDE_CODE_OAUTH_TOKEN` entry (from `claude setup-token`); the API-key / Vertex rungs supply
 *   their own vars. Passed in by A6's wiring — A3 never reads a token itself.
 * @property model optional `--model` override (e.g. `sonnet` for a cheap/fast CI run); `null` uses
 *   the CLI's default.
 * @property maxBudgetUsd the `--max-budget-usd` cap; a trip surfaces as an `error_max_budget_usd`
 *   result subtype. `null` omits the flag.
 * @property wallClockTimeout the worker-side wall-clock cap (there is **no** `--max-turns` flag in
 *   the pinned CLI — see `03-cli-notes.md`); exceeding it kills the process tree and throws.
 * @property toolPolicy the non-interactive permission posture (see [ToolPolicy]).
 * @property pinnedCliVersion the `claude --version` string this engine is built against, recorded
 *   for the banner; verified live at worker startup, not in A3's code paths.
 */
data class HrsClaudeEngineConfig(
    val authEnvironment: Map<String, String>,
    val model: String? = null,
    val maxBudgetUsd: Double? = defaultMaxBudgetUsd,
    val wallClockTimeout: Duration = defaultWallClockTimeout,
    val toolPolicy: ToolPolicy = ToolPolicy.Default,
    val pinnedCliVersion: String = defaultPinnedCliVersion,
) {
  /**
   * The tool/permission policy, materialized as CLI flags. Non-interactive by construction: no
   * prompt may block a headless run.
   *
   * @property allowedTools passed as a single space-separated `--allowedTools` argument.
   * @property disallowedTools passed as a single space-separated `--disallowedTools` argument;
   *   git-push and GitHub tooling live here (publishing belongs to Flow's publisher), plus web
   *   tools which are off by default in M4.
   * @property permissionMode the `--permission-mode` value.
   * @property settingSources the `--setting-sources` value — `project` loads the target repo's
   *   `.claude/` + `CLAUDE.md` while excluding the host's `~/.claude` (hermeticity).
   */
  data class ToolPolicy(
      val allowedTools: List<String>,
      val disallowedTools: List<String>,
      val permissionMode: String,
      val settingSources: String,
  ) {
    companion object {
      /** The design's default posture (01-claude-engine.md + 03-cli-notes.md). */
      val Default =
          ToolPolicy(
              allowedTools = listOf("Read", "Edit", "Write", "Bash", "Glob", "Grep"),
              disallowedTools = listOf("Bash(git push:*)", "Bash(gh:*)", "WebFetch", "WebSearch"),
              permissionMode = "acceptEdits",
              settingSources = "project",
          )
    }
  }

  companion object {
    // A runaway-guard, not a target: a real multi-file task (e.g. a repo-wide removal touching
    // frontend + backend + proto + a DB migration) legitimately spends a few dollars of Claude
    // tool-calls, so a sub-dollar cap guillotines genuine work mid-run (observed on flow#132's
    // re-run). The worker overrides this per-env via FLOW_CLAUDE_MAX_BUDGET_USD.
    const val defaultMaxBudgetUsd = 5.00
    val defaultWallClockTimeout: Duration = 30.minutes

    /** The CLI version A1 verified the flag surface against; bumped by deliberate PRs. */
    const val defaultPinnedCliVersion = "2.1.52 (Claude Code)"
  }
}
