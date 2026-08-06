package software.medusa.flow.harness.assistance

import java.util.logging.Logger
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiReasoningEffort
import software.medusa.commons.openai_client.OaiResponseFormat
import software.medusa.commons.openai_client.messages.OaiMessage
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiToolOutputMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.openai_client.messages.OaiUserName
import software.medusa.commons.openai_client.tools.OaiToolCall
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.ai_system.decodeStructured
import software.medusa.flow.harness.ai_system.extractAssistantMessage
import software.medusa.flow.harness.history.HrsBranchJournalRendering.renderAssistantJournal
import software.medusa.flow.harness.history.HrsChunkSummaryKind
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.history.HrsRawChunkSummary
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree.VedWorktree_renderingUtils.renderDirectoryTree

/**
 * The real [HrsAssistant]: a plain agentic tool-calling loop over an [HrsToolbox].
 *
 * The thread's message prefix — `[system][full journal][task definition][worktree orientation]` —
 * is built once and never rewritten; only tool rounds (assistant turn + this round's tool outputs)
 * append to the tail. Nothing in the thread carries a timestamp (every worktree mutation this
 * delegation makes is stamped at the caller's fixed [HrsToolbox] timestamp, out of the model's
 * sight) — so the whole prefix, and every already-closed round, is byte-stable turn over turn: the
 * classic "append-only, cache-friendly by construction" shape.
 *
 * [openaiClient] must already be configured with the tool definitions the [HrsToolbox] passed to
 * [runDelegation] will advertise (typically [HrsProperToolbox.toolDefinitions]) — configuring the
 * client is a one-time, tool-set-wide concern the caller owns, same as every other `HrsProper*`
 * class in this package takes an already-configured client.
 *
 * Never throws to report a stuck thread: running out of [maxRounds], or producing
 * [maxConsecutiveUnproductiveRounds] rounds in a row with no successful tool call (an unknown tool,
 * bad JSON, a domain error the toolbox rejected, ...), both end the thread with a synthesized
 * `Failed` [HrsDelegationReport] instead — mirroring the bounded-retry, honest-failure shape
 * [software.medusa.flow.harness.HrsProperTaskCompleter] uses for the classic engine's own
 * patch-robustness retries.
 *
 * **The delegation gate.** A `done` call is never taken at face value: [HrsToolbox.checkGate] is
 * run authoritatively right then, ignoring whatever the assistant's own `run_checks` calls showed
 * earlier in the thread. Green accepts the report as-is. Red bounces the diagnostics into this same
 * live thread as the next turn (a plain user message, not a thrown exception or a fresh process —
 * the M1 iterate-until-green shape, nested one layer inside a single delegation) and the loop
 * continues, up to [bounceBudget] bounces. Exhausting the budget does not throw either: the
 * already-produced report is rewritten — `outcome` forced to
 * [software.medusa.flow.harness.history.HrsDelegationOutcome.Failed], `checksSummary` replaced with
 * the final diagnostics — and returned normally. Either way the leader only ever sees the report:
 * raw diagnostics never escape this thread.
 *
 * **Chunk summaries (story 07).** Every [HrsAssistant.Result] carries a [HrsChunkSummarizer] built
 * from this thread's own final message list — the executor calls it after the fact, if and only if
 * this delegation happened to close a chunk, to get one extra structured summary out of the same,
 * already fully-cached thread rather than spinning up a separate compactor role. Requires a second,
 * separately configured [chunkSummaryClient] ([OaiConfiguredClient] fixes its response format at
 * construction, so the same client can't serve both the tool-calling loop and this structured
 * request); leaving it `null` makes every summary request degrade to `null` too.
 */
class HrsProperAssistant(
    private val openaiClient: OaiConfiguredClient,
    private val chunkSummaryClient: OaiConfiguredClient? = null,
    private val maxRounds: Int = defaultMaxRounds,
    private val maxConsecutiveUnproductiveRounds: Int = defaultMaxConsecutiveUnproductiveRounds,
    private val bounceBudget: Int = defaultBounceBudget,
) : HrsAssistant {
  companion object {
    const val defaultMaxRounds = 40
    const val defaultMaxConsecutiveUnproductiveRounds = 3

    /**
     * The JSON response format [chunkSummaryClient] must be configured with (no tools) — built from
     * [HrsRawChunkSummary]'s serializer, same `commons` 0.2.0 fixed-at-configuration-time
     * constraint as [software.medusa.flow.harness.leadership.HrsProperLeader.responseFormat]. A
     * thread that never needs chunk summaries can leave [chunkSummaryClient] `null` and skip
     * configuring this entirely.
     */
    val chunkSummaryResponseFormat: OaiResponseFormat =
        OaiResponseFormat.Json(
            name = "chunk_summary",
            schema =
                SerializationClassJsonSchemaGenerator.Default.generateSchema(
                    target = HrsRawChunkSummary.serializer().descriptor,
                ),
        )

    /**
     * How many times a red gate at `done` may be bounced back into the live thread before the
     * delegation gives up and reports an honest failure. Deliberately small: each bounce is a full
     * analyze+test gate run plus another model round, so the cost of one wrong `done` compounds
     * fast — mirrors [software.medusa.flow.harness.claude.HrsClaudeTaskCompleter]'s bounceBudget
     * for the Claude Agent engine's own top-level gate.
     */
    const val defaultBounceBudget = 2

    private val logger = Logger.getLogger(HrsProperAssistant::class.java.name)

    private const val simpleAiName = "ai"

    private val inferenceParams = OaiInferenceParams(reasoningEffort = OaiReasoningEffort.High)

    private val systemIntroText =
        """
        You are the assistant in an agentic coding loop, working one delegation at a time. Cooperate. Work only through tool calls — a reply with no tool call does nothing and wastes a round.

        You have direct worktree access through tools: `expand_directory`, `peek_file`, `open_file`, `patch`, `expose_file`, `hide_file`, `run_checks`. `peek_file` shows a file's content without opening it — nothing is remembered afterward. `open_file` opens it for real, so it joins this delegation's record. `patch` edits, creates, or deletes files with their full new content, and implies opening any closed file it edits. `expose_file` / `hide_file` control whether a file's settled content sits on the leader's board.

        Below: the full branch journal (every past delegation), then this delegation's task, then the current worktree tree (structure only — open a file to see its content).

        When the task is fully handled, or you must give up, call `done` exactly once with your report — that ends the thread. Don't call `done` alongside other tool calls in the same turn.
        """
            .trimIndent()

    private val noToolCallNudgeText =
        "That reply called no tool, so nothing happened. Call a tool. When you are done — or stuck — call `done` with your report."
  }

  override suspend fun runDelegation(
      context: HrsAssistanceContext,
      taskDefinition: HrsTaskDefinition,
      toolbox: HrsToolbox,
      observer: Observer,
  ): HrsAssistant.Result {
    val prefixMessages = buildPrefixMessages(context = context, taskDefinition = taskDefinition)

    var worktree = context.worktree
    var tailMessages: List<OaiMessage> = emptyList()
    var consecutiveUnproductiveRounds = 0
    var roundNumber = 1
    var bounceCount = 0

    while (roundNumber <= maxRounds) {
      val assistantMessage =
          openaiClient
              .completeChat(
                  chatHistory = OaiChatHistory(messages = prefixMessages + tailMessages),
                  inferenceParams = inferenceParams,
              )
              .extractAssistantMessage()

      assistantMessage?.let { observer.observeRawAssistantResponse(responseText = it.toString()) }

      if (assistantMessage == null || assistantMessage.toolCalls.isEmpty()) {
        tailMessages =
            tailMessages +
                listOfNotNull(assistantMessage) +
                listOf(
                    OaiUserMessage(content = noToolCallNudgeText, name = OaiUserName(simpleAiName))
                )

        consecutiveUnproductiveRounds += 1
      } else {
        val round =
            executeRound(
                toolCalls = assistantMessage.toolCalls,
                worktree = worktree,
                toolbox = toolbox,
            )

        tailMessages = tailMessages + assistantMessage + round.outputMessages
        worktree = round.worktree

        if (round.report != null) {
          when (val gate = toolbox.checkGate()) {
            HrsToolbox.GateOutcome.Healthy ->
                return HrsAssistant.Result(
                    report = round.report,
                    finalWorktree = worktree,
                    chunkSummarizer =
                        buildChunkSummarizer(threadMessages = prefixMessages + tailMessages),
                )

            is HrsToolbox.GateOutcome.Unhealthy -> {
              if (bounceCount >= bounceBudget) {
                logger.info(
                    "Delegation gate still red after $bounceCount bounce(s) (budget " +
                        "$bounceBudget) — returning an honest failure report instead of the " +
                        "assistant's own `done` report.",
                )
                return HrsAssistant.Result(
                    report =
                        exhaustedGateReport(
                            original = round.report,
                            gate = gate,
                            bounceCount = bounceCount,
                        ),
                    finalWorktree = worktree,
                    chunkSummarizer =
                        buildChunkSummarizer(threadMessages = prefixMessages + tailMessages),
                )
              }

              bounceCount += 1
              logger.info(
                  "Delegation gate red at `done` (bounce $bounceCount/$bounceBudget) — bouncing " +
                      "diagnostics back into the live thread.",
              )
              tailMessages =
                  tailMessages + gateBounceMessage(gate = gate, bounceCount = bounceCount)
              consecutiveUnproductiveRounds = 0
              roundNumber += 1
              continue
            }
          }
        }

        consecutiveUnproductiveRounds =
            if (round.anySucceeded) 0 else consecutiveUnproductiveRounds + 1
      }

      if (consecutiveUnproductiveRounds > maxConsecutiveUnproductiveRounds) {
        return HrsAssistant.Result(
            report =
                exhaustedReport(
                    "The assistant produced $consecutiveUnproductiveRounds rounds in a row with " +
                        "no successful tool call.",
                ),
            finalWorktree = worktree,
            chunkSummarizer = buildChunkSummarizer(threadMessages = prefixMessages + tailMessages),
        )
      }

      roundNumber += 1
    }

    return HrsAssistant.Result(
        report =
            exhaustedReport(
                "The assistant did not call `done` within the $maxRounds-round budget."
            ),
        finalWorktree = worktree,
        chunkSummarizer = buildChunkSummarizer(threadMessages = prefixMessages + tailMessages),
    )
  }

  private fun buildPrefixMessages(
      context: HrsAssistanceContext,
      taskDefinition: HrsTaskDefinition,
  ): List<OaiMessage> =
      listOf(
          OaiSystemMessage(content = systemIntroText),
          OaiSystemMessage(
              content =
                  context.delegationLog
                      .renderAssistantJournal(flatWorktree = context.worktree.flatten())
                      .render(),
          ),
          OaiUserMessage(content = taskDefinition.markdown, name = OaiUserName(simpleAiName)),
          OaiSystemMessage(content = context.worktree.renderDirectoryTree().render()),
      )

  /**
   * Captures [threadMessages] — this thread's final prefix+tail, exactly as the model last saw it —
   * into an [HrsChunkSummarizer] the executor can call later, if and when a chunk closes. Without a
   * [chunkSummaryClient] there is nothing to call, so every request degrades to `null` up front
   * rather than the caller having to special-case a missing client.
   */
  private fun buildChunkSummarizer(
      threadMessages: List<OaiMessage>,
  ): HrsChunkSummarizer {
    val client = chunkSummaryClient ?: return HrsChunkSummarizer.unavailable

    return HrsChunkSummarizer { delegationRange, kind ->
      runCatching {
            client
                .completeChat(
                    chatHistory =
                        OaiChatHistory(
                            messages =
                                threadMessages +
                                    chunkSummaryRequestMessage(
                                        delegationRange = delegationRange,
                                        kind = kind,
                                    ),
                        ),
                    inferenceParams = inferenceParams,
                )
                .decodeStructured(deserializer = HrsRawChunkSummary.serializer())
                .toChunkSummary()
          }
          .getOrNull()
    }
  }

  private fun chunkSummaryRequestMessage(
      delegationRange: IntRange,
      kind: HrsChunkSummaryKind,
  ): OaiUserMessage =
      OaiUserMessage(
          content =
              when (kind) {
                HrsChunkSummaryKind.SmallChunk ->
                    "This delegation just closed a chunk covering delegations t=$delegationRange. " +
                        "Using the branch journal above and this thread's own task and report, " +
                        "write a dense Markdown summary of those delegations for the leader's " +
                        "future reference — from now on the leader sees only this summary, not the " +
                        "delegations themselves."

                HrsChunkSummaryKind.BigChunk ->
                    "A larger chunk of delegations, t=$delegationRange, just closed. Using the " +
                        "full, uncompressed delegation history above (never any prior summaries), " +
                        "write a single dense Markdown summary of that whole range for the " +
                        "leader's future reference."
              },
          name = OaiUserName(simpleAiName),
      )

  private data class RoundOutcome(
      val worktree: VedWorktree,
      val outputMessages: List<OaiToolOutputMessage>,
      val report: HrsDelegationReport?,
      val anySucceeded: Boolean,
  )

  private suspend fun executeRound(
      toolCalls: List<OaiToolCall>,
      worktree: VedWorktree,
      toolbox: HrsToolbox,
  ): RoundOutcome {
    var currentWorktree = worktree
    var anySucceeded = false
    var report: HrsDelegationReport? = null
    val outputs = mutableListOf<OaiToolOutputMessage>()

    for (call in toolCalls) {
      if (report != null) {
        outputs += toolOutput(call, "Ignored — `done` was already called earlier this turn.")
        continue
      }

      when (
          val outcome =
              toolbox.execute(
                  toolName = call.toolName.name,
                  rawArguments = call.passedArgument,
                  worktree = currentWorktree,
              )
      ) {
        is HrsToolbox.ToolOutcome.Applied -> {
          currentWorktree = outcome.newWorktree
          anySucceeded = true
          outputs += toolOutput(call, outcome.resultText)
        }

        is HrsToolbox.ToolOutcome.Rejected -> {
          outputs += toolOutput(call, "Error: ${outcome.guidanceText}")
        }

        is HrsToolbox.ToolOutcome.Finished -> {
          anySucceeded = true
          report = outcome.report
          outputs +=
              toolOutput(call, "Report received; checking the gate before ending the thread.")
        }
      }
    }

    return RoundOutcome(
        worktree = currentWorktree,
        outputMessages = outputs,
        report = report,
        anySucceeded = anySucceeded,
    )
  }

  private fun toolOutput(
      call: OaiToolCall,
      text: String,
  ): OaiToolOutputMessage = OaiToolOutputMessage(callId = call.callId, output = text)

  private fun exhaustedReport(
      narrative: String,
  ): HrsDelegationReport =
      HrsDelegationReport(
          outcome = HrsDelegationOutcome.Failed,
          narrative = narrative,
          filesTouched = "(unknown — the thread ended before it could report)",
          bufferChanges = "",
          checksSummary = "",
      )

  /**
   * The message fed back into the live thread when a `done` report's gate comes back red: the raw
   * diagnostics plus the fix-and-retry instruction. Never returned to the caller — only ever a turn
   * in this thread, so the leader (which only ever sees the eventual [HrsDelegationReport]) never
   * sees it.
   */
  private fun gateBounceMessage(
      gate: HrsToolbox.GateOutcome.Unhealthy,
      bounceCount: Int,
  ): OaiUserMessage =
      OaiUserMessage(
          content =
              "That report was not accepted: the authoritative gate is still red (bounce " +
                  "$bounceCount/$bounceBudget). Diagnostics:\n\n${gate.diagnosticsText}\n\n" +
                  "Fix these and call `done` again only once the checks actually pass.",
          name = OaiUserName(simpleAiName),
      )

  /**
   * Rewrites an assistant-produced report into an honest failure once the bounce budget is
   * exhausted: `outcome` is forced to [HrsDelegationOutcome.Failed] and `checksSummary` replaced
   * with the final diagnostics, since neither can be trusted from an assistant that just claimed
   * `done` against a gate that was still red — every other field (narrative, files touched, buffer
   * changes, surprises, leader notices) is preserved as-is.
   */
  private fun exhaustedGateReport(
      original: HrsDelegationReport,
      gate: HrsToolbox.GateOutcome.Unhealthy,
      bounceCount: Int,
  ): HrsDelegationReport =
      original.copy(
          outcome = HrsDelegationOutcome.Failed,
          checksSummary =
              "The gate never went green after $bounceCount bounce(s) back into this thread. " +
                  "Final diagnostics:\n\n${gate.diagnosticsText}",
      )
}
