package software.medusa.flow.harness.assistance

import java.util.logging.Logger
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiReasoningEffort
import software.medusa.commons.openai_client.messages.OaiMessage
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiToolOutputMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.openai_client.messages.OaiUserName
import software.medusa.commons.openai_client.tools.OaiToolCall
import software.medusa.flow.harness.ai_system.extractAssistantMessage
import software.medusa.flow.harness.history.HrsBranchJournalRendering.renderAssistantJournal
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
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
 */
class HrsProperAssistant(
    private val openaiClient: OaiConfiguredClient,
    private val maxRounds: Int = defaultMaxRounds,
    private val maxConsecutiveUnproductiveRounds: Int = defaultMaxConsecutiveUnproductiveRounds,
    private val bounceBudget: Int = defaultBounceBudget,
) : HrsAssistant {
  companion object {
    const val defaultMaxRounds = 40
    const val defaultMaxConsecutiveUnproductiveRounds = 3

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
                return HrsAssistant.Result(report = round.report, finalWorktree = worktree)

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
