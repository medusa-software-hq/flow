package software.medusa.flow.harness.leadership

import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.serialization.json.Json
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiReasoningEffort
import software.medusa.commons.openai_client.OaiResponseFormat
import software.medusa.commons.openai_client.messages.OaiMessage
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.openai_client.messages.OaiUserName
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.ai_system.extractAssistantText
import software.medusa.flow.harness.history.HrsLeaderHistoryRendering.renderLeaderHistory
import software.medusa.flow.virtual_editor.worktree.VedWorktree_leaderRenderingUtils.renderLeaderBoard

/**
 * The real [HrsLeader]: a single structured-output call over a curated, bounded context — no tool
 * calls (an accepted project constraint on the leader role: it only ever emits a
 * [HrsRawLeaderCommand]) and no Markdown-parsing fallback, unlike the classic engine's frontline.
 *
 * The rendered prompt is exactly [HrsLeaderContext]'s four pieces, in order: a fixed system
 * preamble, the overall task, the chunk-summarized history ([renderLeaderHistory] — never
 * snapshots), and the leader board ([renderLeaderBoard] — exposed content, hidden-file stubs, and
 * the exposure meter). Every piece besides the preamble is rendered fresh from [HrsLeaderContext]
 * each call, so a hidden file or a compressed history chunk costs only its stub/summary line, never
 * its full content — the prompt is bounded by construction, not by truncation.
 *
 * [openaiClient] must already be configured with [responseFormat] — fixing the JSON schema at
 * configuration time is a `commons` 0.2.0 constraint, same as every other structured-output class
 * in this package (see [software.medusa.flow.harness.ai_system.HrsAiScoutDecisionInterpreter]).
 *
 * A malformed or refused response (bad JSON, a missing required field, prose instead of structured
 * output) is retried in place — the parse error is fed back as the next turn in the same
 * conversation — up to [maxAttempts] times; exhausting the budget ends the turn with
 * [HrsLeader.Result.Failed] rather than throwing, mirroring
 * [software.medusa.flow.harness.assistance.HrsProperAssistant]'s honest-failure caps.
 */
class HrsProperLeader(
    private val openaiClient: OaiConfiguredClient,
    private val maxAttempts: Int = defaultMaxAttempts,
) : HrsLeader {
  companion object {
    const val defaultMaxAttempts = 3

    private const val simpleAiName = "ai"

    /**
     * The JSON response format the leader's [openaiClient] must be configured with, built from
     * [HrsRawLeaderCommand]'s serializer — the `Delegate(taskDefinition, hideList) | Stop` schema.
     */
    val responseFormat: OaiResponseFormat =
        OaiResponseFormat.Json(
            name = "leader_command",
            schema =
                SerializationClassJsonSchemaGenerator.Default.generateSchema(
                    target = HrsRawLeaderCommand.serializer().descriptor,
                ),
        )

    private val inferenceParams = OaiInferenceParams(reasoningEffort = OaiReasoningEffort.High)

    private val systemPreambleText =
        """
        You are the leader in an agentic coding engine. You never touch the worktree yourself — every unit of real work happens through a delegation you hand to an assistant, who runs an agentic tool-calling loop against the worktree and reports back. Delegate everything non-trivial: orientation, planning-by-doing, implementation, and cleanup are all just delegations with different task definitions — there are no fixed phases.

        Below, in order: the overall task, the branch history (older delegations compressed into summaries, the most recent shown in full), and the leader board — the worktree tree plus the current content of every exposed file. Timestamps on the board (`opened at t=N`, `edited at t=N`) are delegation indices: they cite the "Delegation t=N" entry in the history where that content was produced.

        Mind the leader-buffer meter at the top of the board. It is a soft budget, not a hard one — but when it runs high, spend a line of your next task definition asking the assistant to hide files that have settled and are no longer needed on the board, rather than letting it grow unchecked.

        Write each task definition as clear, natural-language Markdown for the assistant. Give it a concrete, checkable goal, and steer its use of the exposure buffer: ask it to expose the interfaces and files that the next delegation (or you) will need to see, and to keep settled implementation detail hidden. State what "done" looks like, including any checks it should run before reporting.

        Use `hideList` for mechanical buffer cleanup you can decide without judgment — files already on the board whose content you can see are safe to take down before the next delegation starts (hiding needs no reasoning, so it costs no round; exposing does, so it stays the assistant's call). Stop once the task is complete, or once you judge further delegation unproductive.
        """
            .trimIndent()
  }

  override suspend fun decide(
      context: HrsLeaderContext,
      observer: Observer,
  ): HrsLeader.Result {
    val prefixMessages = buildPrefixMessages(context = context)

    var tailMessages: List<OaiMessage> = emptyList()
    var lastParseError: Throwable? = null

    repeat(maxAttempts) {
      val responseText =
          openaiClient
              .completeChat(
                  chatHistory = OaiChatHistory(messages = prefixMessages + tailMessages),
                  inferenceParams = inferenceParams,
              )
              .extractAssistantText()

      observer.observeRawLeaderResponse(responseText = responseText)

      val parseOutcome = runCatching {
        Json.decodeFromString(
            deserializer = HrsRawLeaderCommand.serializer(),
            string = responseText,
        )
      }

      parseOutcome.getOrNull()?.let { rawCommand ->
        return HrsLeader.Result.Decided(command = rawCommand.toCommand())
      }

      lastParseError = parseOutcome.exceptionOrNull()

      tailMessages =
          tailMessages +
              listOf(
                  OaiUserMessage(
                      content = feedbackText(parseError = lastParseError),
                      name = OaiUserName(simpleAiName),
                  ),
              )
    }

    return HrsLeader.Result.Failed(
        reason =
            "The leader produced no valid structured command in $maxAttempts attempt(s); " +
                "last error: ${lastParseError?.message}",
    )
  }

  private fun buildPrefixMessages(
      context: HrsLeaderContext,
  ): List<OaiMessage> =
      listOf(
          OaiSystemMessage(content = systemPreambleText),
          OaiUserMessage(
              content = context.mainTask.body.render(),
              name = OaiUserName(simpleAiName),
          ),
          OaiSystemMessage(
              content =
                  context.delegationLog.renderLeaderHistory(config = context.chunkConfig).render(),
          ),
          OaiSystemMessage(
              content =
                  context.worktree
                      .renderLeaderBoard(softBudgetTokens = context.softBudgetTokens)
                      .render(),
          ),
      )

  private fun feedbackText(
      parseError: Throwable?,
  ): String =
      "That reply did not parse as the required structured command" +
          (parseError?.message?.let { message -> ": $message" } ?: ".") +
          " Respond again with a single structured command matching the schema — no prose."
}
