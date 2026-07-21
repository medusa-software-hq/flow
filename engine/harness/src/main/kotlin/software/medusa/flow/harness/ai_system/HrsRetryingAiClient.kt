package software.medusa.flow.harness.ai_system

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import software.medusa.commons.openai_client.OaiChatHistory
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiInferenceParams
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * An [OaiConfiguredClient] decorator that retries a transient empty response from the model — a
 * [OaiResult.NetworkError], or a received-but-[OaiResponse.Corrupted] response (no usable content,
 * e.g. no choices or null text). Those conditions are provider-driven (a flaky upstream behind
 * OpenRouter); re-issuing the identical request re-rolls the provider's sampling/routing, so a
 * blind retry usually clears it.
 *
 * Wrapping at the [OaiConfiguredClient] boundary covers every LLM call in the pipeline — frontline
 * prose, expert plan, and the structured interpreters all go through the same [completeChat] — in
 * one place, instead of a check at each call site.
 *
 * Everything else is returned unchanged, including an interrupted response ([OaiResponse.Complete]
 * carrying [software.medusa.commons.openai_client.OaiGeneratedContent.Partial] — the model hit its
 * token limit / was filtered) and an [OaiResponse.Error]: neither is cleared by re-rolling the same
 * request, and the operator needs to see it — the remedy is a shorter prompt or a larger token
 * budget, not a retry.
 */
class HrsRetryingAiClient(
    private val delegate: OaiConfiguredClient,
    private val maxAttempts: Int = 3,
    private val retryDelay: Duration = 500.milliseconds,
) : OaiConfiguredClient {
  init {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1, was $maxAttempts" }
  }

  override suspend fun completeChat(
      chatHistory: OaiChatHistory,
      inferenceParams: OaiInferenceParams,
  ): OaiResult<OaiResponse> {
    var attempt = 1

    while (true) {
      val result = delegate.completeChat(chatHistory, inferenceParams)

      // Out of retries, or a result that a re-roll wouldn't improve — return it as-is.
      if (!result.isTransientEmpty() || attempt >= maxAttempts) return result

      attempt += 1
      delay(retryDelay)
    }
  }
}

/**
 * A transient, provider-driven empty response worth re-rolling: the request never reached a usable
 * response ([OaiResult.NetworkError]) or came back [OaiResponse.Corrupted].
 */
private fun OaiResult<OaiResponse>.isTransientEmpty(): Boolean =
    this is OaiResult.NetworkError ||
        (this is OaiResult.ResponseReceived && response is OaiResponse.Corrupted)
