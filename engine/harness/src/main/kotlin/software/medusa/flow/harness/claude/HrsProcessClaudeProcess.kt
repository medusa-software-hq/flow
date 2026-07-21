package software.medusa.flow.harness.claude

import java.io.BufferedWriter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import software.medusa.commons.system.SysExecutableHandle

/**
 * The production [HrsClaudeProcess]: drives the real `claude` binary via [ProcessBuilder].
 *
 * The [claudeExecutable] is located **once, at CLI startup** (like `npm`/`yarn`, see
 * `cli/.../main.kt`) and injected here — so a missing binary is a clean, loud failure at boot, not
 * a surprise deep inside the first session. `claude` is a hard dependency of the worker (the image
 * ships it); a test harness that cannot provide it wires an [UnimplementedHrsTaskCompleter] for the
 * claude engine instead of a real process.
 *
 * Two deliberate properties from the design:
 * - **Replace-not-inherit environment.** The child gets exactly [HrsClaudeInvocation.environment] —
 *   the JVM's own env is cleared first, so no host `ANTHROPIC_*`/`CLAUDE_*`/`~/.claude` leaks in.
 * - **Process-tree kill on close.** [close] destroys the process and all descendants, honoring the
 *   worker's subprocess-cleanup contract on cancellation/abandonment.
 *
 * Not exercised by A3's unit tests (that would require the real, un-nestable CLI — see
 * 03-cli-notes.md); the completer is tested against `FakeHrsClaudeProcess` instead.
 */
class HrsProcessClaudeProcess(
    private val claudeExecutable: SysExecutableHandle,
) : HrsClaudeProcess {
  override fun spawn(
      invocation: HrsClaudeInvocation,
  ): HrsClaudeRun {
    val processBuilder =
        ProcessBuilder(listOf(claudeExecutable.path.toString()) + invocation.arguments)
            .directory(invocation.workingDirectory.toFile())

    // Replace-not-inherit: strip the JVM's environment, then install exactly what was requested.
    processBuilder.environment().clear()
    processBuilder.environment().putAll(invocation.environment)

    val process =
        try {
          processBuilder.start()
        } catch (e: Exception) {
          throw HrsClaudeEngineException.binaryUnavailable(cause = e)
        }

    return ProcessRun(process = process)
  }

  private class ProcessRun(
      private val process: Process,
  ) : HrsClaudeRun {
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()

    // stderr must be drained concurrently with stdout or the child can block on a full pipe; a
    // plain daemon thread keeps this handle free of any coroutine scope.
    private val stderrBuffer = StringBuilder()
    private val stderrThread =
        Thread {
              process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                  synchronized(stderrBuffer) { stderrBuffer.appendLine(line) }
                }
              }
            }
            .apply {
              isDaemon = true
              start()
            }

    override val messages: Flow<HrsClaudeMessage> =
        flow {
              process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                  HrsClaudeStreamParser.parseLine(line)?.let { emit(it) }
                }
              }
            }
            .flowOn(Dispatchers.IO)

    override suspend fun sendUserMessage(
        text: String,
    ) {
      withContext(Dispatchers.IO) {
        // The stream-json input envelope the CLI expects for a user turn.
        stdin.write(renderUserMessageLine(text))
        stdin.newLine()
        stdin.flush()
      }
    }

    override suspend fun awaitTermination(): HrsClaudeRun.Termination {
      val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
      stderrThread.join(STDERR_JOIN_MILLIS)
      return HrsClaudeRun.Termination(
          exitCode = exitCode,
          standardError = synchronized(stderrBuffer) { stderrBuffer.toString() },
      )
    }

    override fun close() {
      // Kill descendants first, then the root, so nothing reparents and survives.
      process.descendants().forEach { it.destroyForcibly() }
      process.destroyForcibly()
      try {
        process.waitFor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
      try {
        stdin.close()
      } catch (_: Exception) {
        // best-effort
      }
    }

    private companion object {
      const val STDERR_JOIN_MILLIS = 2_000L
      const val CLOSE_WAIT_SECONDS = 5L
    }
  }

  private companion object {
    /** Wraps raw text as a single-line `stream-json` user message. */
    fun renderUserMessageLine(
        text: String,
    ): String =
        buildJsonObject {
              put("type", "user")
              putJsonObject("message") {
                put("role", "user")
                put("content", text)
              }
            }
            .toString()
  }
}
