package software.medusa.flow.harness.claude

/**
 * The `claude` engine's own operational failures — the driver could not run a session to a clean
 * verdict, as opposed to the session running and reporting an unhealthy solution (which A4 will
 * model as a structured `TaskCompletionResult.Failure`).
 *
 * These are **thrown**, not returned: the M1 worker catches them and calls `failSession` with the
 * message, so every message here is written to name the cause and, where possible, the fix. See the
 * factories for the taxonomy (01-claude-engine.md's failure table).
 */
class HrsClaudeEngineException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
  enum class Kind {
    BinaryUnavailable,
    AuthFailure,
    ResultError,
    CapExceeded,
    SubprocessFailure,
    Timeout,
  }

  companion object {
    private const val STDERR_TAIL_LIMIT = 2_000

    fun binaryUnavailable(
        cause: Throwable,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.BinaryUnavailable,
            message =
                "The `claude` CLI was not found on PATH. Install it and pin the expected version " +
                    "(see 03-cli-notes.md), then set it on the worker.",
            cause = cause,
        )

    fun authFailure(
        authRung: String,
        detail: String,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.AuthFailure,
            message =
                "The `claude` CLI failed to authenticate on the '$authRung' auth rung. Refresh the " +
                    "credential for that rung and retry. Detail: $detail",
        )

    fun capExceeded(
        subtype: String,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.CapExceeded,
            message =
                "The `claude` session hit a configured cap ($subtype) before completing. Raise the " +
                    "cap or narrow the task.",
        )

    fun resultError(
        subtype: String?,
        lastAssistantText: String?,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.ResultError,
            message =
                "The `claude` session ended in an error result (subtype=${subtype ?: "<none>"}). " +
                    "Last assistant message: ${lastAssistantText?.ifBlank { "<empty>" } ?: "<none>"}",
        )

    fun missingResult(
        exitCode: Int,
        standardError: String,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.SubprocessFailure,
            message =
                "The `claude` subprocess exited (code=$exitCode) without emitting a terminal " +
                    "`result` message. stderr tail:\n${standardError.takeStderrTail()}",
        )

    /**
     * The process itself is authoritative: a non-zero exit is a failure even when the terminal
     * `result` message (if any) reported success. Without this, a run that crashes at the process
     * level after streaming a `success` result would sail through as
     * [TaskCompletionResult.Success].
     */
    fun processFailed(
        exitCode: Int,
        standardError: String,
        lastAssistantText: String?,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.SubprocessFailure,
            message =
                "The `claude` subprocess exited with a non-zero code (exit=$exitCode); the process " +
                    "outcome is authoritative even if its result payload reported success. Last " +
                    "assistant message: ${lastAssistantText?.ifBlank { "<empty>" } ?: "<none>"}. " +
                    "stderr tail:\n${standardError.takeStderrTail()}",
        )

    fun timedOut(
        timeout: String,
        standardError: String,
    ): HrsClaudeEngineException =
        HrsClaudeEngineException(
            kind = Kind.Timeout,
            message =
                "The `claude` subprocess exceeded its wall-clock timeout ($timeout) and was killed. " +
                    "stderr tail:\n${standardError.takeStderrTail()}",
        )

    private fun String.takeStderrTail(): String =
        if (length <= STDERR_TAIL_LIMIT) this else "…" + takeLast(STDERR_TAIL_LIMIT)
  }
}
