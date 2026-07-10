package software.medusa.flow.harness.ai_system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.schema.json.JsonSchema
import software.medusa.commons.openai_client.OaiConfiguredClient

/**
 * An [OaiConfiguredClient] decorator that retries an "empty response" from the model — a response
 * with no choices, or a choice with null text content. That condition is transient and
 * provider-driven (see [HrsEmptyAiResponseException]); re-issuing the identical request re-rolls
 * the provider's sampling/routing, so a blind retry is usually enough.
 *
 * Wrapping at the [OaiConfiguredClient] boundary covers every LLM call in the pipeline — frontline
 * prose, expert plan, and the structured interpreters all go through the same two methods — in one
 * place, instead of a try/catch at each call site.
 *
 * Only the empty-response family is retried; any other failure is rethrown unchanged, so genuine
 * bugs (bad request, schema mismatch, auth) still fail fast rather than being retried three times.
 */
class HrsRetryingAiClient(
    private val delegate: OaiConfiguredClient,
    private val maxAttempts: Int = 3,
    private val retryDelay: Duration = 500.milliseconds,
) : OaiConfiguredClient {
  init {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
  }

  companion object {
    /**
     * Messages the OpenAI client uses for the empty-response condition. Matched by substring
     * because the library raises a bare `IllegalStateException` — there's no typed exception to
     * catch (the proper long-term fix is a typed `OaiEmptyResponseException` in
     * `software.medusa.commons:openai-client`; swap this predicate for a type check once it
     * exists).
     */
    private val emptyResponseMessageMarkers =
        listOf(
            "did not contain text content",
            "did not contain any choices",
        )

    private fun isEmptyResponseFailure(
        throwable: Throwable,
    ): Boolean {
      val message = throwable.message ?: return false
      return emptyResponseMessageMarkers.any { marker -> message.contains(marker) }
    }
  }

  override suspend fun createUnstructuredCompletion(
      request: OaiConfiguredClient.CompletionRequest,
  ): OaiConfiguredClient.UnstructuredCompletionResponse = withEmptyResponseRetry {
    delegate.createUnstructuredCompletion(request)
  }

  override suspend fun createRawStructuredCompletion(
      request: OaiConfiguredClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
  ): OaiConfiguredClient.RawStructuredCompletionResponse = withEmptyResponseRetry {
    delegate.createRawStructuredCompletion(request, responseSchemaName, responseSchema)
  }

  override fun close() = delegate.close()

  private suspend fun <T> withEmptyResponseRetry(
      completion: suspend () -> T,
  ): T {
    var lastEmptyResponseFailure: Throwable? = null

    repeat(maxAttempts) { attemptIndex ->
      try {
        return completion()
      } catch (throwable: Throwable) {
        if (!isEmptyResponseFailure(throwable)) throw throwable

        lastEmptyResponseFailure = throwable

        val isLastAttempt = attemptIndex == maxAttempts - 1
        if (!isLastAttempt) delay(retryDelay)
      }
    }

    throw HrsEmptyAiResponseException(
        message =
            "The model returned an empty response $maxAttempts time(s) in a row. Last error: " +
                "${lastEmptyResponseFailure?.message}",
        cause = lastEmptyResponseFailure,
    )
  }
}
