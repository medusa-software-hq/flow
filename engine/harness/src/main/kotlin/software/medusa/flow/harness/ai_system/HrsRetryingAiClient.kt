package software.medusa.flow.harness.ai_system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.schema.json.JsonSchema
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiEmptyResponseException

/**
 * An [OaiConfiguredClient] decorator that retries a transient empty response from the model — no
 * choices, or a choice with null text content ([OaiEmptyResponseException]). That condition is
 * provider-driven (a flaky upstream behind OpenRouter); re-issuing the identical request re-rolls
 * the provider's sampling/routing, so a blind retry usually clears it.
 *
 * Wrapping at the [OaiConfiguredClient] boundary covers every LLM call in the pipeline — frontline
 * prose, expert plan, and the structured interpreters all go through the same two methods — in one
 * place, instead of a try/catch at each call site.
 *
 * Everything else is rethrown unchanged, including
 * [OaiIncompleteResponseException][software.medusa.commons.openai_client.OaiIncompleteResponseException]
 * (the model hit its token limit / was filtered): that's not cleared by re-rolling the same
 * request, and the operator needs to see it — the remedy is a shorter prompt or a larger token
 * budget, not a retry. Genuine bugs (bad request, schema mismatch, auth) likewise fail fast.
 */
class HrsRetryingAiClient(
    private val delegate: OaiConfiguredClient,
    private val maxAttempts: Int = 3,
    private val retryDelay: Duration = 500.milliseconds,
) : OaiConfiguredClient {
  init {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
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
    var attempt = 1

    while (true) {
      try {
        return completion()
      } catch (emptyResponse: OaiEmptyResponseException) {
        // Out of retries — let the (typed, readable) failure propagate.
        if (attempt >= maxAttempts) throw emptyResponse

        attempt += 1
        delay(retryDelay)
      }
    }
  }
}
