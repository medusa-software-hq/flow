package software.medusa.openai_client

import java.net.URI
import kotlinx.schema.generator.json.serialization.SerializationClassJsonSchemaGenerator
import kotlinx.schema.json.JsonSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

interface OpenAiClient : AutoCloseable {
  data class Config(
      val baseUrl: URI,
      val apiKey: String,
      val organization: String? = null,
  )

  data class CompletionRequest(
      val input: OpenAiChat,
      val model: OpenAiModel,
      val maxOutputTokenCount: Int? = null,
      val temperature: Double? = null,
  )

  data class UnstructuredCompletionResponse(
      val responseText: String,
  )

  data class RawStructuredCompletionResponse(
      val responseJsonElement: JsonElement,
  )

  data class StructuredCompletionResponse<ResponseT : Any>(
      val responseObject: ResponseT,
  )

  companion object {
    val openAiBaseUrl: URI = URI.create("https://api.openai.com/v1/")

    val openRouterBaseUrl: URI = URI.create("https://openrouter.ai/api/v1/")

    fun build(
        config: Config,
    ): OpenAiClient =
        ProperOpenAiClient.build(
            config = config,
        )
  }

  suspend fun createUnstructuredCompletion(
      request: CompletionRequest,
  ): UnstructuredCompletionResponse

  suspend fun createRawStructuredCompletion(
      request: CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
  ): RawStructuredCompletionResponse
}

// https://developers.openai.com/api/docs/guides/structured-outputs#refusals
private const val refusalFieldName = "refusal"

suspend fun <ResponseT : Any> OpenAiClient.createStructuredCompletion(
    /** Completion request. */
    request: OpenAiClient.CompletionRequest,
    /** Schema name (used for debugging). */
    responseSchemaName: String = "response_schema",
    /**
     * Response type serializer. Should not include a root field named `refusal`, as it's reserved.
     */
    responseSerializer: KSerializer<ResponseT>,
): OpenAiClient.StructuredCompletionResponse<ResponseT> {
  val generator = SerializationClassJsonSchemaGenerator.Default
  val responseSchema = generator.generateSchema(target = responseSerializer.descriptor)

  val rawResponse =
      createRawStructuredCompletion(
          request = request,
          responseSchemaName = responseSchemaName,
          responseSchema = responseSchema,
      )

  val responseJsonElement = rawResponse.responseJsonElement

  val refusalElement = (responseJsonElement as JsonObject?)?.get(refusalFieldName)

  if (refusalElement != null) {
    throw IllegalStateException(
        "OpenAI refused to complete the request. Refusal reason: $refusalElement",
    )
  }

  val responseObject =
      Json.decodeFromJsonElement(
          deserializer = responseSerializer,
          element = responseJsonElement,
      )

  return OpenAiClient.StructuredCompletionResponse(
      responseObject = responseObject,
  )
}
