package software.medusa.flow.harness.claude

import java.nio.file.Path
import kotlinx.coroutines.flow.Flow

/**
 * A streaming seam over one run of the `claude` CLI subprocess.
 *
 * This mirrors the toolchains' `NjsCommand` interface-impl-fake shape, but the batch
 * `SysProcessSpawner` (collect-then-return) can't drive an agent that emits progress and, for A4,
 * must be fed stdin mid-run — so this is a handle model instead: [spawn] starts the process and
 * hands back an [HrsClaudeRun] whose stdout is a live [HrsClaudeRun.messages] flow.
 *
 * The real implementation ([HrsProcessClaudeProcess]) drives [ProcessBuilder]; tests use
 * `FakeHrsClaudeProcess`, which replays canned messages without touching a real binary.
 */
interface HrsClaudeProcess {
  fun spawn(
      invocation: HrsClaudeInvocation,
  ): HrsClaudeRun
}

/**
 * Everything needed to launch the subprocess. The environment is **constructed, not inherited**
 * (see [HrsProcessClaudeProcess]): [environment] is the exact set handed to the process — auth vars
 * for the active rung, model override, PATH, and nothing else.
 */
data class HrsClaudeInvocation(
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: Path,
)

/**
 * A live handle to the running subprocess.
 *
 * Lifecycle: collect [messages] to consume stdout, then [awaitTermination] for the exit code and
 * captured stderr; always [close] (kills the process tree) — a `use { }` block is the intended
 * pattern so cancellation/timeout tears the tree down.
 */
interface HrsClaudeRun : AutoCloseable {
  /**
   * Parsed NDJSON stdout messages, in order; the flow completes when the process closes stdout.
   * Cold: collecting it consumes the single underlying stream (do not collect twice).
   */
  val messages: Flow<HrsClaudeMessage>

  /**
   * Feeds a user message to the process's stdin as a `stream-json` input line. Unused by A3's
   * single-shot driver; it exists now because A4's gate-bounce loop resumes the *same* live process
   * by writing the per-module diagnostics here rather than re-spawning. The `text` is wrapped by
   * the implementation into the `{"type":"user","message":{…}}` envelope the CLI expects.
   */
  suspend fun sendUserMessage(
      text: String,
  )

  /** Suspends until the process exits, then reports how it ended. */
  suspend fun awaitTermination(): Termination

  /** Kills the process tree. Idempotent; safe to call after normal termination. */
  override fun close()

  data class Termination(
      val exitCode: Int,
      /** Captured stderr (the driver surfaces its tail in failure messages). */
      val standardError: String,
  )
}
