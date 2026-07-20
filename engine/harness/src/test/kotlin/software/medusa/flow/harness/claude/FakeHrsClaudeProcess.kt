package software.medusa.flow.harness.claude

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow

/**
 * A test [HrsClaudeProcess] that replays caller-supplied canned message sequences without spawning
 * any real binary. Mirrors the toolchains' `FakeNjsCommand` role for this seam.
 *
 * Each entry of [cannedRuns] is the stream for one successive [spawn] call, so A4's resume/bounce
 * loop (which spawns a *fresh* process per bounce) can be given a distinct stream per attempt. When
 * more spawns happen than there are canned runs, the last run is replayed.
 *
 * Every [spawn]'s [HrsClaudeInvocation] is recorded in [invocations] (so tests can assert on the
 * flags/env the completer built — including a bounce's `--resume` + diagnostics prompt), along with
 * any stdin messages in [sentMessages].
 */
class FakeHrsClaudeProcess
private constructor(
    private val cannedRuns: List<List<HrsClaudeMessage>>,
    private val termination: HrsClaudeRun.Termination,
) : HrsClaudeProcess {
  companion object {
    private val defaultTermination = HrsClaudeRun.Termination(exitCode = 0, standardError = "")

    /** Single-shot: one canned stream replayed for every spawn (the A3 shape). */
    operator fun invoke(
        cannedMessages: List<HrsClaudeMessage>,
        termination: HrsClaudeRun.Termination = defaultTermination,
    ): FakeHrsClaudeProcess =
        FakeHrsClaudeProcess(cannedRuns = listOf(cannedMessages), termination = termination)

    /** Multi-run: successive spawns replay successive streams (A4's resume/bounce shape). */
    fun withRuns(
        cannedRuns: List<List<HrsClaudeMessage>>,
        termination: HrsClaudeRun.Termination = defaultTermination,
    ): FakeHrsClaudeProcess {
      require(cannedRuns.isNotEmpty()) { "at least one canned run is required" }
      return FakeHrsClaudeProcess(cannedRuns = cannedRuns, termination = termination)
    }
  }

  /** Every invocation this fake was spawned with, in order. */
  val invocations: MutableList<HrsClaudeInvocation> = mutableListOf()

  val lastInvocation: HrsClaudeInvocation?
    get() = invocations.lastOrNull()

  val spawnCount: Int
    get() = invocations.size

  val sentMessages: MutableList<String> = mutableListOf()

  private var closedCount: Int = 0

  /** True once every spawned run has been closed (process trees killed). */
  val closed: Boolean
    get() = invocations.isNotEmpty() && closedCount == invocations.size

  override fun spawn(
      invocation: HrsClaudeInvocation,
  ): HrsClaudeRun {
    val runIndex = invocations.size
    invocations += invocation
    val cannedMessages = cannedRuns[runIndex.coerceAtMost(cannedRuns.size - 1)]

    return object : HrsClaudeRun {
      override val messages: Flow<HrsClaudeMessage> = cannedMessages.asFlow()

      override suspend fun sendUserMessage(
          text: String,
      ) {
        sentMessages += text
      }

      override suspend fun awaitTermination(): HrsClaudeRun.Termination = termination

      override fun close() {
        closedCount += 1
      }
    }
  }
}
