package software.medusa.flow.harness.ai_system

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import software.medusa.commons.openai_client.OaiGeneratedContent
import software.medusa.commons.openai_client.OaiResponse
import software.medusa.commons.openai_client.OaiResult

/**
 * Bridges the coarse [OaiResult]/[OaiResponse] tree the 0.2.0 `openai-client` returns back to the
 * plain "extract the assistant's text" shape the rest of the harness was written against.
 *
 * The library forwards the underlying detail of every anomaly it collapses (network failure, empty
 * or corrupted response, …) to the [software.medusa.commons.openai_client.OaiReporter] configured
 * on the client — see [HrsLoggingOaiReporter] — so there is nothing to log again here:
 * - a [OaiResult.NetworkError] and an [OaiResponse.Corrupted] map to an empty string (the
 *   transient-empty case [HrsRetryingAiClient] retries before we ever see it);
 * - an interrupted ([OaiGeneratedContent.Partial]) response surfaces its best-available partial
 *   text rather than throwing (best-effort — the old code raised here, but there is no exception
 *   channel left and the partial text is the most useful thing to hand back);
 * - an [OaiResponse.Error] maps to an empty string.
 */
internal fun OaiResult<OaiResponse>.extractAssistantText(): String =
    when (this) {
      OaiResult.NetworkError -> ""
      is OaiResult.ResponseReceived ->
          when (val received = response) {
            is OaiResponse.Complete ->
                when (val content = received.generatedContent) {
                  is OaiGeneratedContent.Full -> content.generatedMessage.content.orEmpty()
                  is OaiGeneratedContent.Partial -> content.partialGeneratedText.orEmpty()
                }
            OaiResponse.Corrupted -> ""
            is OaiResponse.Error -> ""
          }
    }

/**
 * Extracts the assistant text (see [extractAssistantText]) and parses it into [ResponseT] with the
 * given [deserializer] — the 0.2.0 replacement for the old `createStructuredCompletion`, where the
 * JSON response format is now fixed on the
 * [software.medusa.commons.openai_client.OaiConfiguredClient] instead of per call.
 */
internal fun <ResponseT> OaiResult<OaiResponse>.decodeStructured(
    deserializer: DeserializationStrategy<ResponseT>,
): ResponseT = Json.decodeFromString(deserializer = deserializer, string = extractAssistantText())
