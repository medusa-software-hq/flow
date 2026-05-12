package software.medusa.openai_client

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatResponseFormat
import com.aallam.openai.api.chat.JsonSchema
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlinx.schema.json.encodeToJsonObject
import kotlinx.serialization.json.Json

internal class ProperOpenAiClient(
    private val openAi: OpenAI,
) : OpenAiClient {
  companion object {
    fun build(
        config: OpenAiClient.Config,
    ): ProperOpenAiClient {
      val host =
          OpenAIHost(
              baseUrl = config.baseUrl.toASCIIString(),
          )

      return ProperOpenAiClient(
          openAi =
              OpenAI(
                  OpenAIConfig(
                      token = config.apiKey,
                      organization = config.organization,
                      host = host,
                  ),
              ),
      )
    }
  }

  override suspend fun createUnstructuredCompletion(
      request: OpenAiClient.CompletionRequest,
  ): OpenAiClient.UnstructuredCompletionResponse {
    val chatCompletion =
        openAi.chatCompletion(
            ChatCompletionRequest(
                model = request.model.toSdkModelId(),
                messages = request.input.toSdkMessages(),
                maxCompletionTokens = request.maxOutputTokenCount,
                temperature = request.temperature,
            ),
        )

    val responseText = chatCompletion.extractResponseContent()

    return OpenAiClient.UnstructuredCompletionResponse(
        responseText = responseText,
    )
  }

  override suspend fun createRawStructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: kotlinx.schema.json.JsonSchema,
  ): OpenAiClient.RawStructuredCompletionResponse {
    val responseSchemaJsonObject = responseSchema.encodeToJsonObject()

    val chatCompletion =
        openAi.chatCompletion(
            ChatCompletionRequest(
                model = request.model.toSdkModelId(),
                messages = request.input.toSdkMessages(),
                responseFormat =
                    ChatResponseFormat.jsonSchema(
                        JsonSchema(
                            name = responseSchemaName,
                            schema = responseSchemaJsonObject,
                        ),
                    ),
                maxCompletionTokens = request.maxOutputTokenCount,
                temperature = request.temperature,
            ),
        )

    val responseJsonText = chatCompletion.extractResponseContent()

    val responseJsonElement =
        Json.parseToJsonElement(
            string = responseJsonText,
        )

    return OpenAiClient.RawStructuredCompletionResponse(
        responseJsonElement = responseJsonElement,
    )
  }

  override fun close() {
    openAi.close()
  }
}

private fun ChatCompletion.extractResponseContent(): String {
  val firstChoice =
      choices.firstOrNull()
          ?: throw IllegalStateException("OpenAI response did not contain any choices")

  val message = firstChoice.message

  val messageContent =
      message.content
          ?: throw IllegalStateException("OpenAI response choice did not contain text content")

  return messageContent
}

private fun String.ensureHasTrailingSlash(): String = if (endsWith('/')) this else "$this/"
