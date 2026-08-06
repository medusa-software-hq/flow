package software.medusa.flow.harness.assistance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult
import software.medusa.commons.openai_client.OaiTokenUsage
import software.medusa.commons.openai_client.messages.OaiAssistantMessage
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiToolOutputMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.openai_client.tools.OaiToolCall
import software.medusa.commons.openai_client.tools.OaiToolCallId
import software.medusa.commons.openai_client.tools.OaiToolName
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsChunkSummaryKind
import software.medusa.flow.harness.history.HrsDelegationEntry
import software.medusa.flow.harness.history.HrsDelegationLog
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.history.HrsRawChunkSummary
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Covers [HrsProperAssistant]'s loop mechanics against a scripted client and a [FakeHrsToolbox]:
 * tool dispatch across rounds, `done` ending the thread (alone or alongside other calls), and both
 * of the honest-failure caps (round budget, consecutive-unproductive-round budget) firing instead
 * of a throw.
 */
class HrsProperAssistant_tests {
  private companion object {
    private val anyTokenUsage =
        OaiTokenUsage(promptTokenCount = 0, completionTokenCount = 0, totalTokenCount = 0)

    private val emptyWorktree =
        VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

    private val anyContext =
        HrsAssistanceContext(
            mainTask =
                HrsTaskDescription(
                    body =
                        MdChapter.leaf(
                            title = MdInlineContent.of("Task"),
                            element = MdElement.Empty,
                        ),
                ),
            delegationLog = HrsDelegationLog(),
            worktree = emptyWorktree,
        )

    private val anyTaskDefinition = HrsTaskDefinition(markdown = "Do the thing.")

    private fun toolCall(
        name: String,
        callId: String,
        args: JsonElement = buildJsonObject {},
    ): OaiToolCall =
        OaiToolCall(
            toolName = OaiToolName(name),
            callId = OaiToolCallId(callId),
            passedArgument = args,
        )

    private fun toolCallResponse(
        calls: List<OaiToolCall>,
        content: String = "",
    ): OaiResult<OaiResponse> =
        OaiResult.ResponseReceived(
            OaiResponse.Complete(
                generatedContent =
                    OaiGeneratedContent.Full(
                        generatedMessage =
                            OaiAssistantMessage(content = content, toolCalls = calls),
                    ),
                tokenUsage = anyTokenUsage,
            ),
        )

    private fun plainTextResponse(
        content: String,
    ): OaiResult<OaiResponse> = toolCallResponse(calls = emptyList(), content = content)

    private fun summaryResponse(
        markdown: String,
    ): OaiResult<OaiResponse> =
        plainTextResponse(
            content =
                Json.encodeToString(HrsRawChunkSummary.serializer(), HrsRawChunkSummary(markdown)),
        )

    private fun doneReportArgs(
        report: HrsDelegationReport,
    ): JsonElement = Json.encodeToJsonElement(HrsDelegationReport.serializer(), report)

    private val anyReport =
        HrsDelegationReport(
            outcome = HrsDelegationOutcome.Done,
            narrative = "did the thing",
            filesTouched = "- `/a.txt` — edited",
            bufferChanges = "",
            checksSummary = "green",
        )
  }

  /**
   * Replays [responses] one per successive [completeChat] call; the last one repeats past the end.
   */
  private class ScriptedOaiClient(
      private val responses: List<OaiResult<OaiResponse>>,
  ) : OaiConfiguredClient {
    val chatHistories = mutableListOf<OaiChatHistory>()

    val callCount: Int
      get() = chatHistories.size

    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> {
      chatHistories += chatHistory
      return responses[(chatHistories.size - 1).coerceAtMost(responses.size - 1)]
    }
  }

  /**
   * A toolbox whose `done` calls always finish the thread with the decoded report (matching
   * [HrsProperToolbox]'s real behavior); every other tool goes through [nonDoneBehavior].
   */
  private fun toolboxHandlingDone(
      gateBehavior: () -> HrsToolbox.GateOutcome = { HrsToolbox.GateOutcome.Healthy },
      nonDoneBehavior: (String, JsonElement, VedWorktree) -> HrsToolbox.ToolOutcome,
  ): FakeHrsToolbox =
      FakeHrsToolbox(
          defaultBehavior = { toolName, rawArguments, worktree ->
            if (toolName == "done") {
              HrsToolbox.ToolOutcome.Finished(
                  report =
                      Json.decodeFromJsonElement(HrsDelegationReport.serializer(), rawArguments),
              )
            } else {
              nonDoneBehavior(toolName, rawArguments, worktree)
            }
          },
          gateBehavior = gateBehavior,
      )

  private fun applyingToolbox(
      resultText: String = "ok",
      gateBehavior: () -> HrsToolbox.GateOutcome = { HrsToolbox.GateOutcome.Healthy },
  ): FakeHrsToolbox =
      toolboxHandlingDone(gateBehavior = gateBehavior) { _, _, worktree ->
        HrsToolbox.ToolOutcome.Applied(newWorktree = worktree, resultText = resultText)
      }

  @Test
  fun `a single done call ends the thread with its report`() = runBlocking {
    val client =
        ScriptedOaiClient(
            responses =
                listOf(toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))))
        )
    val toolbox = applyingToolbox()
    val assistant = HrsProperAssistant(openaiClient = client)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    assertEquals(anyReport, result.report)
    assertEquals(emptyWorktree, result.finalWorktree)
    assertEquals(1, client.callCount)
    assertEquals(listOf("done"), toolbox.calls.map { it.toolName })
  }

  @Test
  fun `tool calls across rounds thread the worktree and accumulate the tail before done`() =
      runBlocking {
        val worktreeAfterFirstCall =
            VedWorktree(rootDirectory = VedExpandedDirectory(labeledEntityByName = emptyMap()))

        val toolbox =
            FakeHrsToolbox(
                behaviorByToolName =
                    mapOf(
                        "expand_directory" to
                            { _, _ ->
                              HrsToolbox.ToolOutcome.Applied(
                                  newWorktree = worktreeAfterFirstCall,
                                  resultText = "expanded",
                              )
                            },
                        "done" to
                            { args, _ ->
                              HrsToolbox.ToolOutcome.Finished(
                                  report =
                                      Json.decodeFromJsonElement(
                                          HrsDelegationReport.serializer(),
                                          args,
                                      ),
                              )
                            },
                    ),
            )

        val client =
            ScriptedOaiClient(
                responses =
                    listOf(
                        toolCallResponse(
                            listOf(
                                toolCall(
                                    "expand_directory",
                                    "c1",
                                    buildJsonObject { put("path", JsonPrimitive("/src")) },
                                )
                            )
                        ),
                        toolCallResponse(listOf(toolCall("done", "c2", doneReportArgs(anyReport)))),
                    ),
            )

        val assistant = HrsProperAssistant(openaiClient = client)

        val result =
            assistant.runDelegation(
                context = anyContext,
                taskDefinition = anyTaskDefinition,
                toolbox = toolbox,
            )

        assertEquals(anyReport, result.report)
        assertEquals(worktreeAfterFirstCall, result.finalWorktree)
        assertEquals(2, client.callCount)

        // The second call's history carries the first round's assistant turn and its tool output.
        val secondHistoryMessages = client.chatHistories[1].messages
        assertTrue(secondHistoryMessages.filterIsInstance<OaiAssistantMessage>().isNotEmpty())
        assertTrue(
            secondHistoryMessages.filterIsInstance<OaiToolOutputMessage>().any {
              it.output == "expanded"
            }
        )
      }

  @Test
  fun `a plain reply with no tool call is nudged and does not end the thread`() = runBlocking {
    val toolbox = applyingToolbox()

    val client =
        ScriptedOaiClient(
            responses =
                listOf(
                    plainTextResponse("I will think about it."),
                    toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))),
                ),
        )

    val assistant = HrsProperAssistant(openaiClient = client)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    assertEquals(anyReport, result.report)
    assertEquals(2, client.callCount)
  }

  @Test
  fun `done alongside another call in the same round still ends the thread`() = runBlocking {
    val toolbox = applyingToolbox()

    val client =
        ScriptedOaiClient(
            responses =
                listOf(
                    toolCallResponse(
                        listOf(
                            toolCall(
                                "open_file",
                                "c1",
                                buildJsonObject { put("path", JsonPrimitive("/a.txt")) },
                            ),
                            toolCall("done", "c2", doneReportArgs(anyReport)),
                        ),
                    ),
                ),
        )

    val assistant = HrsProperAssistant(openaiClient = client)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    assertEquals(anyReport, result.report)
    assertEquals(listOf("open_file", "done"), toolbox.calls.map { it.toolName })
  }

  @Test
  fun `a persistently rejected tool call ends the thread honestly instead of looping forever`() =
      runBlocking {
        val toolbox =
            FakeHrsToolbox(
                defaultBehavior = { toolName, _, _ ->
                  HrsToolbox.ToolOutcome.Rejected(guidanceText = "`$toolName` is not a real path.")
                },
            )

        val client =
            ScriptedOaiClient(
                responses =
                    listOf(
                        toolCallResponse(
                            listOf(
                                toolCall(
                                    "open_file",
                                    "c1",
                                    buildJsonObject { put("path", JsonPrimitive("/nope")) },
                                )
                            )
                        ),
                    ),
            )

        val assistant =
            HrsProperAssistant(openaiClient = client, maxConsecutiveUnproductiveRounds = 2)

        val result =
            assistant.runDelegation(
                context = anyContext,
                taskDefinition = anyTaskDefinition,
                toolbox = toolbox,
            )

        assertEquals(HrsDelegationOutcome.Failed, result.report.outcome)
        // 3 unproductive rounds needed to exceed a budget of 2.
        assertEquals(3, client.callCount)
      }

  @Test
  fun `a rejection streak that recovers does not trip the unproductive-round cap`() = runBlocking {
    var rejectNextCalls = 2

    val toolbox = toolboxHandlingDone { _, _, worktree ->
      if (rejectNextCalls > 0) {
        rejectNextCalls -= 1
        HrsToolbox.ToolOutcome.Rejected(guidanceText = "not yet")
      } else {
        HrsToolbox.ToolOutcome.Applied(newWorktree = worktree, resultText = "ok")
      }
    }

    val client =
        ScriptedOaiClient(
            responses =
                listOf(
                    toolCallResponse(listOf(toolCall("open_file", "c1"))),
                    toolCallResponse(listOf(toolCall("open_file", "c2"))),
                    toolCallResponse(listOf(toolCall("open_file", "c3"))),
                    toolCallResponse(listOf(toolCall("done", "c4", doneReportArgs(anyReport)))),
                ),
        )

    val assistant = HrsProperAssistant(openaiClient = client, maxConsecutiveUnproductiveRounds = 2)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    assertEquals(anyReport, result.report)
    assertEquals(4, client.callCount)
  }

  @Test
  fun `running out of the round budget ends the thread honestly instead of looping forever`() =
      runBlocking {
        val toolbox = applyingToolbox()
        val client = ScriptedOaiClient(responses = listOf(plainTextResponse("still thinking")))

        val assistant =
            HrsProperAssistant(
                openaiClient = client,
                maxRounds = 4,
                maxConsecutiveUnproductiveRounds = 100,
            )

        val result =
            assistant.runDelegation(
                context = anyContext,
                taskDefinition = anyTaskDefinition,
                toolbox = toolbox,
            )

        assertEquals(HrsDelegationOutcome.Failed, result.report.outcome)
        assertEquals(4, client.callCount)
      }

  @Test
  fun `a done report is accepted immediately when the authoritative gate is green`() = runBlocking {
    val client =
        ScriptedOaiClient(
            responses =
                listOf(toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))))
        )
    val toolbox = applyingToolbox()
    val assistant = HrsProperAssistant(openaiClient = client)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    assertEquals(anyReport, result.report)
    assertEquals(1, client.callCount)
    assertEquals(listOf<HrsToolbox.GateOutcome>(HrsToolbox.GateOutcome.Healthy), toolbox.gateChecks)
  }

  @Test
  fun `a red gate at done bounces diagnostics into the thread and accepts a later green done`() =
      runBlocking {
        var gateCallCount = 0
        val toolbox =
            toolboxHandlingDone(
                gateBehavior = {
                  gateCallCount += 1
                  if (gateCallCount == 1) {
                    HrsToolbox.GateOutcome.Unhealthy(diagnosticsText = "boom: test failed")
                  } else {
                    HrsToolbox.GateOutcome.Healthy
                  }
                },
            ) { _, _, worktree ->
              HrsToolbox.ToolOutcome.Applied(newWorktree = worktree, resultText = "ok")
            }

        val client =
            ScriptedOaiClient(
                responses =
                    listOf(
                        toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))),
                        toolCallResponse(listOf(toolCall("done", "c2", doneReportArgs(anyReport)))),
                    ),
            )

        val assistant = HrsProperAssistant(openaiClient = client)

        val result =
            assistant.runDelegation(
                context = anyContext,
                taskDefinition = anyTaskDefinition,
                toolbox = toolbox,
            )

        // The leader-facing result is the accepted report, unmodified — no raw diagnostics leak
        // into it.
        assertEquals(anyReport, result.report)
        assertEquals(2, client.callCount)
        assertEquals(2, toolbox.gateChecks.size)

        // The diagnostics were bounced into the live thread as this delegation's next turn, not
        // discarded and not thrown.
        val secondHistoryMessages = client.chatHistories[1].messages
        assertTrue(
            secondHistoryMessages.any {
              it is OaiUserMessage && it.content.contains("boom: test failed")
            }
        )
      }

  @Test
  fun `exhausting the bounce budget rewrites the report into an honest failure`() = runBlocking {
    val toolbox =
        toolboxHandlingDone(
            gateBehavior = { HrsToolbox.GateOutcome.Unhealthy(diagnosticsText = "still red") },
        ) { _, _, worktree ->
          HrsToolbox.ToolOutcome.Applied(newWorktree = worktree, resultText = "ok")
        }

    val client =
        ScriptedOaiClient(
            responses =
                listOf(toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))))
        )

    val assistant = HrsProperAssistant(openaiClient = client)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = toolbox,
        )

    // 1 initial `done` + defaultBounceBudget (2) bounces = 3 rounds before giving up.
    assertEquals(3, client.callCount)
    assertEquals(3, toolbox.gateChecks.size)

    assertEquals(HrsDelegationOutcome.Failed, result.report.outcome)
    assertTrue(result.report.checksSummary.contains("still red"))
    // Everything besides the outcome/checksSummary the gate itself governs survives the rewrite
    // untouched.
    assertEquals(anyReport.narrative, result.report.narrative)
    assertEquals(anyReport.filesTouched, result.report.filesTouched)
  }

  @Test
  fun `without a chunk summary client the returned summarizer always degrades to null`() =
      runBlocking {
        val client =
            ScriptedOaiClient(
                responses =
                    listOf(
                        toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport))))
                    )
            )
        val assistant = HrsProperAssistant(openaiClient = client)

        val result =
            assistant.runDelegation(
                context = anyContext,
                taskDefinition = anyTaskDefinition,
                toolbox = applyingToolbox(),
            )

        assertNull(
            result.chunkSummarizer.summarize(
                delegationRange = 0..2,
                kind = HrsChunkSummaryKind.SmallChunk,
            ),
        )
      }

  @Test
  fun `a configured chunk summary client produces the summary it returns`() = runBlocking {
    val client =
        ScriptedOaiClient(
            responses =
                listOf(toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))))
        )
    val summaryClient = ScriptedOaiClient(responses = listOf(summaryResponse("zzsummaryzz")))

    val assistant = HrsProperAssistant(openaiClient = client, chunkSummaryClient = summaryClient)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = applyingToolbox(),
        )

    val summary =
        result.chunkSummarizer.summarize(
            delegationRange = 0..2,
            kind = HrsChunkSummaryKind.SmallChunk,
        )

    assertEquals(HrsChunkSummary("zzsummaryzz"), summary)
    assertEquals(1, summaryClient.callCount)
  }

  @Test
  fun `a malformed summary response degrades to null instead of throwing`() = runBlocking {
    val client =
        ScriptedOaiClient(
            responses =
                listOf(toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport)))))
        )
    val summaryClient = ScriptedOaiClient(responses = listOf(plainTextResponse("not valid json")))

    val assistant = HrsProperAssistant(openaiClient = client, chunkSummaryClient = summaryClient)

    val result =
        assistant.runDelegation(
            context = anyContext,
            taskDefinition = anyTaskDefinition,
            toolbox = applyingToolbox(),
        )

    assertNull(
        result.chunkSummarizer.summarize(
            delegationRange = 0..2,
            kind = HrsChunkSummaryKind.BigChunk,
        ),
    )
  }

  @Test
  fun `a big-chunk summary request carries the thread's full journal, not any prior summary`() =
      runBlocking {
        val contextWithHistory =
            anyContext.copy(
                delegationLog =
                    HrsDelegationLog(
                        entries =
                            listOf(
                                HrsDelegationEntry(
                                    taskDefinition = HrsTaskDefinition(markdown = "zzpriortaskzz"),
                                    report = anyReport.copy(narrative = "zzpriornarrativezz"),
                                ),
                            ),
                    ),
            )

        val client =
            ScriptedOaiClient(
                responses =
                    listOf(
                        toolCallResponse(listOf(toolCall("done", "c1", doneReportArgs(anyReport))))
                    )
            )
        val summaryClient = ScriptedOaiClient(responses = listOf(summaryResponse("zzsummaryzz")))

        val assistant =
            HrsProperAssistant(openaiClient = client, chunkSummaryClient = summaryClient)

        val result =
            assistant.runDelegation(
                context = contextWithHistory,
                taskDefinition = anyTaskDefinition,
                toolbox = applyingToolbox(),
            )

        result.chunkSummarizer.summarize(
            delegationRange = 0..1,
            kind = HrsChunkSummaryKind.BigChunk,
        )

        val sentMessages = summaryClient.chatHistories.single().messages
        assertTrue(
            sentMessages.any {
              it is OaiSystemMessage && it.content.contains("zzpriornarrativezz")
            },
            "the full, never-compacted journal must ride along into the summary request",
        )
      }
}
