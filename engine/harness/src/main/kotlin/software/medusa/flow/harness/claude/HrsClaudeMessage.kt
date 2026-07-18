package software.medusa.flow.harness.claude

/**
 * The subset of the `claude` CLI's `stream-json` (NDJSON) protocol the driver acts on.
 *
 * The wire shapes are only fully pinned by A1's contract test against the real binary, so this
 * model is intentionally partial and lenient: [HrsClaudeStreamParser] tolerates unknown message
 * `type`s and unknown fields, and every field the driver reads is confined to that one parser —
 * protocol drift is therefore a localized fix here, not scattered across the completer.
 */
sealed interface HrsClaudeMessage {
  /**
   * The opening `system`/`init` banner. Carries the identity of the run: [sessionId] (needed by
   * A4's resume/bounce path), the resolved [model], and the [tools] the CLI enabled.
   */
  data class SystemInit(
      val sessionId: String?,
      val model: String?,
      val tools: List<String>,
  ) : HrsClaudeMessage

  /** An `assistant` turn, flattened to its concatenated text blocks (tool_use blocks dropped). */
  data class Assistant(
      val text: String,
  ) : HrsClaudeMessage

  /**
   * The terminal `result` message. [isError] plus [subtype] carry the outcome — including cap trips
   * such as `error_max_budget_usd` — and the accounting fields feed the engine banner/cost event.
   */
  data class Result(
      val isError: Boolean,
      val subtype: String?,
      val totalCostUsd: Double?,
      val numTurns: Int?,
      val durationMs: Long?,
  ) : HrsClaudeMessage

  /** Any message whose `type` the driver does not model — captured so nothing throws on drift. */
  data class Unknown(
      val type: String?,
  ) : HrsClaudeMessage
}
