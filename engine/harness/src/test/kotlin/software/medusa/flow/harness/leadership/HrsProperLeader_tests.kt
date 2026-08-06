package software.medusa.flow.harness.leadership

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.markdown.MdBlock
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
import software.medusa.commons.openai_client.messages.OaiMessage
import software.medusa.commons.openai_client.messages.OaiSystemMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.history.HrsChunkConfig
import software.medusa.flow.harness.history.HrsChunkSummary
import software.medusa.flow.harness.history.HrsDelegationEntry
import software.medusa.flow.harness.history.HrsDelegationLog
import software.medusa.flow.harness.history.HrsDelegationOutcome
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedEntity
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedExposure
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree

/**
 * Prompt-snapshot coverage for [HrsProperLeader]: the rendered prompt carries the preamble, the
 * main task, correctly-tiered history, exposed content, hidden-file stubs, and the exposure meter
 * (golden-marker style, like
 * [software.medusa.flow.harness.history.HrsLeaderHistoryRendering_tests]), the prompt is bounded by
 * construction (a hidden file or a summarized delegation costs only its stub/summary line, never
 * its full content), and the malformed/refused-output retry loop mirrors
 * [software.medusa.flow.harness.assistance.HrsProperAssistant]'s honest-failure shape.
 */
class HrsProperLeader_tests {
  private companion object {
    private val anyTokenUsage =
        OaiTokenUsage(promptTokenCount = 0, completionTokenCount = 0, totalTokenCount = 0)

    private val mainTaskMarker = "ZZMAINTASKZZ"

    private val mainTask =
        HrsTaskDescription(
            body =
                MdChapter.leaf(
                    title = MdInlineContent.of("Task"),
                    element = MdElement(listOf(MdBlock.Paragraph.of(mainTaskMarker))),
                ),
        )

    private fun textContent(
        message: OaiMessage,
    ): String =
        when (message) {
          is OaiSystemMessage -> message.content
          is OaiUserMessage -> message.content
          else -> ""
        }

    private fun renderedPrompt(
        chatHistory: OaiChatHistory,
    ): String = chatHistory.messages.joinToString(separator = "\n") { textContent(it) }

    private fun textResponse(
        content: String,
    ): OaiResult<OaiResponse> =
        OaiResult.ResponseReceived(
            OaiResponse.Complete(
                generatedContent =
                    OaiGeneratedContent.Full(
                        generatedMessage =
                            OaiAssistantMessage(content = content, toolCalls = emptyList()),
                    ),
                tokenUsage = anyTokenUsage,
            ),
        )

    private fun rawCommandResponse(
        raw: HrsRawLeaderCommand,
    ): OaiResult<OaiResponse> =
        textResponse(content = Json.encodeToString(HrsRawLeaderCommand.serializer(), raw))

    private val stopResponse = rawCommandResponse(HrsRawLeaderCommand(stop = true))

    private fun delegationEntry(
        k: Int,
        narrative: String = "did $k",
    ): HrsDelegationEntry =
        HrsDelegationEntry(
            taskDefinition = HrsTaskDefinition(markdown = "zzmark${k}zz"),
            report =
                HrsDelegationReport(
                    outcome = HrsDelegationOutcome.Done,
                    narrative = narrative,
                    filesTouched = "- /f$k",
                    bufferChanges = "none",
                    checksSummary = "green",
                ),
        )

    private fun logOf(
        delegationCount: Int,
    ): HrsDelegationLog =
        HrsDelegationLog(entries = (0 until delegationCount).map { k -> delegationEntry(k) })

    private fun labeled(
        file: VedEntity,
    ): VedExpandedDirectory.LabeledEntity =
        VedExpandedDirectory.LabeledEntity(
            status = GitWorktreeEntity.Status.included,
            entity = file,
        )

    private fun fileContent(
        text: String,
    ): TxtFileContent = TxtFileContent(content = TxtBlock.of(text))

    /**
     * `/exposed.txt` exposed at t=1; `/hidden.txt` opened at t=2 with the given content;
     * `/closed.txt` never opened.
     */
    private fun worktree(
        exposedMarker: String,
        hiddenContent: String,
    ): VedWorktree {
      val exposed =
          VedOpenedFile.of(content = fileContent(exposedMarker), timestamp = VedTimestamp(1))
              .withExposure(VedExposure.Exposed)

      val hidden =
          VedOpenedFile.of(content = fileContent(hiddenContent), timestamp = VedTimestamp(2))

      return VedWorktree(
          rootDirectory =
              VedExpandedDirectory(
                  labeledEntityByName =
                      mapOf(
                          UfsName.Literal("exposed.txt") to labeled(exposed),
                          UfsName.Literal("hidden.txt") to labeled(hidden),
                          UfsName.Literal("closed.txt") to labeled(VedClosedFile),
                      ),
              ),
      )
    }

    private fun context(
        worktree: VedWorktree,
        log: HrsDelegationLog = HrsDelegationLog(),
        chunkConfig: HrsChunkConfig = HrsChunkConfig(smallChunkSize = 2, bigChunkSize = 2),
        softBudgetTokens: Int = 20000,
    ): HrsLeaderContext =
        HrsLeaderContext(
            mainTask = mainTask,
            delegationLog = log,
            worktree = worktree,
            softBudgetTokens = softBudgetTokens,
            chunkConfig = chunkConfig,
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
   * Delegates everything to [Observer.Noop] except [observeRawLeaderResponse], which it records.
   */
  private class RecordingRawResponseObserver : Observer by Observer.Noop {
    val rawLeaderResponses: MutableList<String> = mutableListOf()

    override fun observeRawLeaderResponse(
        responseText: String,
    ) {
      rawLeaderResponses += responseText
    }
  }

  @Test
  fun `the prompt carries the main task, exposed content, hidden stubs, and the meter`() =
      runBlocking {
        val exposedMarker = "ZZEXPOSEDZZ"
        val hiddenMarker = "ZZHIDDENZZ"

        val client = ScriptedOaiClient(responses = listOf(stopResponse))
        val leader = HrsProperLeader(openaiClient = client)

        leader.decide(
            context =
                context(
                    worktree = worktree(exposedMarker = exposedMarker, hiddenContent = hiddenMarker)
                ),
        )

        val prompt = renderedPrompt(client.chatHistories.single())

        assertTrue(prompt.contains(mainTaskMarker), "the main task renders")
        assertTrue(prompt.contains(exposedMarker), "exposed content renders in full")
        assertFalse(prompt.contains(hiddenMarker), "hidden content must not render")
        assertTrue(prompt.contains("hidden.txt"), "the hidden file's stub is listed")
        assertTrue(
            prompt.contains("opened at t=2"),
            "the stub carries a delegation-index timestamp",
        )
        assertTrue(prompt.contains("closed.txt"), "the closed file lists by name")
        assertTrue(prompt.contains("Leader buffer:"), "the exposure meter renders")
      }

  @Test
  fun `the history tiers render oldest-first — big summary, then small summary, then the full window`() =
      runBlocking {
        // smallChunkSize=2, bigChunkSize=2, 10 delegations -> 5 small chunks (0..4).
        // firstWindowChunk = 3 -> fullWindowChunks = [3, 4] (delegations 6..9).
        // numSummarizedBigChunks = 3/2 = 1 -> bigSummaryChunks = [0] (delegations 0..3).
        // smallSummaryChunks = [2] (delegations 4..5).
        val log =
            logOf(10)
                .withBigChunkSummary(chunkIndex = 0, summary = HrsChunkSummary("ZZBIGSUMMARYZZ"))
                .withSmallChunkSummary(
                    chunkIndex = 2,
                    summary = HrsChunkSummary("ZZSMALLSUMMARYZZ"),
                )

        val client = ScriptedOaiClient(responses = listOf(stopResponse))
        val leader = HrsProperLeader(openaiClient = client)

        leader.decide(context = context(worktree = worktree("e", "h"), log = log))

        val prompt = renderedPrompt(client.chatHistories.single())

        val bigSummaryIndex = prompt.indexOf("ZZBIGSUMMARYZZ")
        val smallSummaryIndex = prompt.indexOf("ZZSMALLSUMMARYZZ")
        val fullWindowIndex = prompt.indexOf("zzmark6zz")

        assertTrue(bigSummaryIndex >= 0, "the big-chunk summary renders")
        assertTrue(smallSummaryIndex >= 0, "the small-chunk summary renders")
        assertTrue(fullWindowIndex >= 0, "the full window renders in full")
        assertTrue(bigSummaryIndex < smallSummaryIndex, "big summary precedes small summary")
        assertTrue(smallSummaryIndex < fullWindowIndex, "small summary precedes the full window")

        // The summarized delegations' own task/report text never renders.
        for (k in 0..5) assertFalse(
            prompt.contains("zzmark${k}zz"),
            "summarized delegation $k must not render",
        )
      }

  @Test
  fun `a hidden file's content size does not grow the prompt — only its stub does`() = runBlocking {
    val leanClient = ScriptedOaiClient(responses = listOf(stopResponse))
    HrsProperLeader(openaiClient = leanClient)
        .decide(context = context(worktree = worktree("e", "h")))
    val leanPrompt = renderedPrompt(leanClient.chatHistories.single())

    val hugeHiddenContent = "H".repeat(50_000)
    val bulkyClient = ScriptedOaiClient(responses = listOf(stopResponse))
    HrsProperLeader(openaiClient = bulkyClient)
        .decide(context = context(worktree = worktree("e", hugeHiddenContent)))
    val bulkyPrompt = renderedPrompt(bulkyClient.chatHistories.single())

    assertFalse(bulkyPrompt.contains(hugeHiddenContent), "hidden content never renders")
    // A 50,000-char hidden file must cost far less than its own size — a stub line, not its
    // content.
    assertTrue(
        (bulkyPrompt.length - leanPrompt.length) < 200,
        "prompt growth (${bulkyPrompt.length - leanPrompt.length} chars) must be bounded, not proportional " +
            "to the hidden file's size",
    )
  }

  @Test
  fun `a summarized delegation's size does not grow the prompt — only its summary does`() =
      runBlocking {
        // With the default smallChunkSize=2/bigChunkSize=2 chunkConfig, big chunk 0 covers
        // delegations
        // 0..3; once it has a stored summary, delegation 0's own narrative can be arbitrarily large
        // without ever reaching the prompt.
        fun logWithSummarizedFirstChunk(
            firstNarrative: String,
        ): HrsDelegationLog =
            HrsDelegationLog(
                    entries =
                        listOf(
                            delegationEntry(k = 0, narrative = firstNarrative),
                            delegationEntry(k = 1),
                        ) + (2 until 10).map { k -> delegationEntry(k) },
                )
                .withBigChunkSummary(chunkIndex = 0, summary = HrsChunkSummary("ZZBIGSUMMARYZZ"))

        val leanClient = ScriptedOaiClient(responses = listOf(stopResponse))
        HrsProperLeader(openaiClient = leanClient)
            .decide(
                context =
                    context(
                        worktree = worktree("e", "h"),
                        log = logWithSummarizedFirstChunk("short"),
                    ),
            )
        val leanPrompt = renderedPrompt(leanClient.chatHistories.single())

        val hugeNarrative = "N".repeat(50_000)
        val bulkyClient = ScriptedOaiClient(responses = listOf(stopResponse))
        HrsProperLeader(openaiClient = bulkyClient)
            .decide(
                context =
                    context(
                        worktree = worktree("e", "h"),
                        log = logWithSummarizedFirstChunk(hugeNarrative),
                    ),
            )
        val bulkyPrompt = renderedPrompt(bulkyClient.chatHistories.single())

        assertFalse(
            bulkyPrompt.contains(hugeNarrative),
            "the summarized delegation's own text never renders",
        )
        assertEquals(
            leanPrompt.length,
            bulkyPrompt.length,
            "a summarized entry's size must not affect the prompt",
        )
      }

  @Test
  fun `a malformed reply is retried with the parse error fed back, then succeeds`() = runBlocking {
    val client = ScriptedOaiClient(responses = listOf(textResponse("not valid json"), stopResponse))
    val leader = HrsProperLeader(openaiClient = client)

    val result = leader.decide(context = context(worktree = worktree("e", "h")))

    assertEquals(HrsLeader.Result.Decided(HrsLeaderCommand.Stop), result)
    assertEquals(2, client.callCount)

    val retryPrompt = renderedPrompt(client.chatHistories[1])
    assertTrue(retryPrompt.contains("did not parse"), "the retry carries the parse-error feedback")
  }

  @Test
  fun `observeRawLeaderResponse fires with each raw response text, including a retried one`() =
      runBlocking {
        val client =
            ScriptedOaiClient(responses = listOf(textResponse("not valid json"), stopResponse))
        val leader = HrsProperLeader(openaiClient = client)
        val observer = RecordingRawResponseObserver()

        val result =
            leader.decide(context = context(worktree = worktree("e", "h")), observer = observer)

        assertEquals(HrsLeader.Result.Decided(HrsLeaderCommand.Stop), result)
        assertEquals(2, observer.rawLeaderResponses.size)
        assertEquals("not valid json", observer.rawLeaderResponses[0])
        assertTrue(observer.rawLeaderResponses[1].contains("\"stop\""))
      }

  @Test
  fun `a persistently malformed reply ends in a structured failure, not a throw`() = runBlocking {
    val client = ScriptedOaiClient(responses = listOf(textResponse("still not json")))
    val leader = HrsProperLeader(openaiClient = client, maxAttempts = 2)

    val result = leader.decide(context = context(worktree = worktree("e", "h")))

    assertIs<HrsLeader.Result.Failed>(result)
    assertEquals(2, client.callCount)
  }

  @Test
  fun `a Delegate command round-trips its task definition and hide list`() = runBlocking {
    val raw =
        HrsRawLeaderCommand(
            stop = false,
            taskDefinition = "Do the next thing.",
            hideList = listOf("/src/Main.kt"),
        )
    val client = ScriptedOaiClient(responses = listOf(rawCommandResponse(raw)))
    val leader = HrsProperLeader(openaiClient = client)

    val result = leader.decide(context = context(worktree = worktree("e", "h")))

    assertEquals(
        HrsLeader.Result.Decided(
            HrsLeaderCommand.Delegate(
                taskDefinition = HrsTaskDefinition(markdown = "Do the next thing."),
                hideList = listOf("/src/Main.kt"),
            ),
        ),
        result,
    )
  }
}
