package software.medusa.flow.harness.claude

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * A test [HrsClaudeProcess] that replays a caller-supplied canned message sequence and termination,
 * without spawning any real binary. Mirrors the toolchains' `FakeNjsCommand` role for this seam.
 *
 * It records the [invocation] it was spawned with and any stdin messages [sentMessages], so tests
 * can assert on the flags/env the completer built and (for A4) the resume messages it will send.
 */
class FakeHrsClaudeProcess(
    private val cannedMessages: List<HrsClaudeMessage>,
    private val termination: HrsClaudeRun.Termination =
        HrsClaudeRun.Termination(exitCode = 0, standardError = ""),
) : HrsClaudeProcess {
  var lastInvocation: HrsClaudeInvocation? = null
    private set

  val sentMessages: MutableList<String> = mutableListOf()

  var closed: Boolean = false
    private set

  override fun spawn(
      invocation: HrsClaudeInvocation,
  ): HrsClaudeRun {
    lastInvocation = invocation
    return object : HrsClaudeRun {
      override val messages: Flow<HrsClaudeMessage> = cannedMessages.asFlow()

      override suspend fun sendUserMessage(
          text: String,
      ) {
        sentMessages += text
      }

      override suspend fun awaitTermination(): HrsClaudeRun.Termination = termination

      override fun close() {
        closed = true
      }
    }
  }
}
