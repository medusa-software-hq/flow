package software.medusa.flow.harness.ai_system

import com.linecorp.armeria.common.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiInterruptionReason
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult
import software.medusa.commons.openai_client.OaiTokenUsage
import software.medusa.commons.openai_client.messages.OaiAssistantMessage
import software.medusa.commons.openai_client.messages.OaiUserMessage

class HrsRetryingAiClient_tests {
  private companion object {
    private val anyHistory = OaiChatHistory(messages = listOf(OaiUserMessage(content = "hi")))

    private val anyTokenUsage =
        OaiTokenUsage(promptTokenCount = 0, completionTokenCount = 0, totalTokenCount = 0)

    private val successResult: OaiResult<OaiResponse> =
        OaiResult.ResponseReceived(
            OaiResponse.Complete(
                generatedContent =
                    OaiGeneratedContent.Full(
                        generatedMessage = OaiAssistantMessage(content = "ok")
                    ),
                tokenUsage = anyTokenUsage,
            ),
        )
  }

  /**
   * Returns [failure] for the first [failuresBeforeSuccess] calls, then [successResult]. Both the
   * unstructured and structured paths funnel through the same [completeChat], so covering it proves
   * the retry mechanism for every call site.
   */
  private class ScriptedClient(
      private val failuresBeforeSuccess: Int,
      private val failure: OaiResult<OaiResponse>,
  ) : OaiConfiguredClient {
    var callCount = 0
      private set

    override suspend fun completeChat(
        chatHistory: OaiChatHistory,
        inferenceParams: OaiInferenceParams,
    ): OaiResult<OaiResponse> {
      callCount += 1
      return if (callCount <= failuresBeforeSuccess) failure else successResult
    }
  }

  private fun retryingClient(
      delegate: OaiConfiguredClient,
      maxAttempts: Int = 3,
  ): HrsRetryingAiClient =
      HrsRetryingAiClient(
          delegate = delegate,
          maxAttempts = maxAttempts,
          retryDelay = Duration.ZERO,
      )

  @Test
  fun `a network error is retried and succeeds once the model returns content`() = runBlocking {
    val delegate = ScriptedClient(failuresBeforeSuccess = 2, failure = OaiResult.NetworkError)

    val result = retryingClient(delegate).completeChat(anyHistory)

    assertSame(successResult, result)
    assertEquals(3, delegate.callCount) // 2 network errors + 1 success
  }

  @Test
  fun `a corrupted response is retried and succeeds once the model returns content`() =
      runBlocking {
        val delegate =
            ScriptedClient(
                failuresBeforeSuccess = 1,
                failure = OaiResult.ResponseReceived(OaiResponse.Corrupted),
            )

        val result = retryingClient(delegate).completeChat(anyHistory)

        assertSame(successResult, result)
        assertEquals(2, delegate.callCount)
      }

  @Test
  fun `a persistently transient response is returned coarsely after the retry budget`() =
      runBlocking {
        val delegate =
            ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = OaiResult.NetworkError)

        val result = retryingClient(delegate, maxAttempts = 3).completeChat(anyHistory)

        assertSame(OaiResult.NetworkError, result)
        assertEquals(3, delegate.callCount)
      }

  @Test
  fun `an interrupted (truncated) response is not retried and returned immediately`() =
      runBlocking {
        val interrupted: OaiResult<OaiResponse> =
            OaiResult.ResponseReceived(
                OaiResponse.Complete(
                    generatedContent =
                        OaiGeneratedContent.Partial(
                            partialGeneratedText = "cut short",
                            reasoningText = "",
                            interruptionReason = OaiInterruptionReason.LengthLimit,
                        ),
                    tokenUsage = anyTokenUsage,
                ),
            )
        val delegate = ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = interrupted)

        val result = retryingClient(delegate).completeChat(anyHistory)

        assertSame(interrupted, result)
        assertEquals(1, delegate.callCount) // fail fast — re-rolling won't un-truncate it
      }

  @Test
  fun `an error response is not retried and returned immediately`() = runBlocking {
    val error: OaiResult<OaiResponse> =
        OaiResult.ResponseReceived(
            OaiResponse.Error(status = HttpStatus.BAD_REQUEST, message = "bad request"),
        )
    val delegate = ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = error)

    val result = retryingClient(delegate).completeChat(anyHistory)

    assertSame(error, result)
    assertEquals(1, delegate.callCount) // no retry — an HTTP error isn't cleared by re-rolling
  }
}
