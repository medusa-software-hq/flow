package software.medusa.flow.harness.ai_system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.schema.json.JsonSchema
import software.medusa.commons.openai_client.OaiChat
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiConfiguredClient.CompletionRequest
import software.medusa.commons.openai_client.OaiConfiguredClient.UnstructuredCompletionResponse
import software.medusa.commons.openai_client.OaiConfiguredClient.Usage
import software.medusa.commons.openai_client.OaiEmptyResponseException
import software.medusa.commons.openai_client.OaiIncompleteResponseException
import software.medusa.commons.openai_client.OaiMessage
import software.medusa.commons.openai_client.OaiRole

class HrsRetryingAiClient_tests {
  private companion object {
    private val anyRequest =
        CompletionRequest(
            input = OaiChat(messages = listOf(OaiMessage(role = OaiRole.User, text = "hi"))),
        )
    private val anyResponse =
        UnstructuredCompletionResponse(
            responseText = "ok",
            usage = Usage(0, 0, 0),
        )

    private const val emptyResponseMessage = "OpenAI response choice did not contain text content"
  }

  /**
   * Fails the first [failuresBeforeSuccess] unstructured calls with [failure], then returns
   * [anyResponse]. Only the unstructured path is exercised — both completion methods share the same
   * retry helper, so covering one proves the mechanism.
   */
  private class ScriptedClient(
      private val failuresBeforeSuccess: Int,
      private val failure: Throwable,
  ) : OaiConfiguredClient {
    var callCount = 0
      private set

    override suspend fun createUnstructuredCompletion(
        request: CompletionRequest,
    ): UnstructuredCompletionResponse {
      callCount += 1
      if (callCount <= failuresBeforeSuccess) throw failure
      return anyResponse
    }

    override suspend fun createRawStructuredCompletion(
        request: CompletionRequest,
        responseSchemaName: String,
        responseSchema: JsonSchema,
    ): OaiConfiguredClient.RawStructuredCompletionResponse = error("not used by these tests")

    override fun close() = Unit
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
  fun `an empty response is retried and succeeds once the model returns content`() = runBlocking {
    val delegate =
        ScriptedClient(
            failuresBeforeSuccess = 2,
            failure = OaiEmptyResponseException(emptyResponseMessage),
        )

    val response = retryingClient(delegate).createUnstructuredCompletion(anyRequest)

    assertSame(anyResponse, response)
    assertEquals(3, delegate.callCount) // 2 empty responses + 1 success
  }

  @Test
  fun `a persistently empty response propagates the typed exception after the retry budget`() =
      runBlocking {
        val failure = OaiEmptyResponseException(emptyResponseMessage)
        val delegate = ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = failure)

        val thrown =
            assertFailsWith<OaiEmptyResponseException> {
              retryingClient(delegate, maxAttempts = 3).createUnstructuredCompletion(anyRequest)
            }

        assertSame(failure, thrown)
        assertEquals(3, delegate.callCount)
      }

  @Test
  fun `an incomplete (truncated) response is not retried and propagates immediately`() =
      runBlocking {
        val failure = OaiIncompleteResponseException(finishReason = "length", message = "cut short")
        val delegate = ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = failure)

        val thrown =
            assertFailsWith<OaiIncompleteResponseException> {
              retryingClient(delegate).createUnstructuredCompletion(anyRequest)
            }

        assertSame(failure, thrown)
        assertEquals(1, delegate.callCount) // fail fast — re-rolling won't un-truncate it
      }

  @Test
  fun `an unrelated failure is rethrown immediately, not retried`() = runBlocking {
    val unrelated = IllegalArgumentException("bad request")
    val delegate = ScriptedClient(failuresBeforeSuccess = Int.MAX_VALUE, failure = unrelated)

    val thrown =
        assertFailsWith<IllegalArgumentException> {
          retryingClient(delegate).createUnstructuredCompletion(anyRequest)
        }

    assertSame(unrelated, thrown)
    assertEquals(1, delegate.callCount) // no retry
  }
}
