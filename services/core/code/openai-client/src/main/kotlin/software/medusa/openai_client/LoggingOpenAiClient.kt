package software.medusa.openai_client

import kotlinx.schema.json.JsonSchema

class LoggingOpenAiClient(
    private val baseClient: OpenAiClient,
    private val logger: OpenAiLogger,
) : OpenAiClient {
  override suspend fun createUnstructuredCompletion(
      request: OpenAiClient.CompletionRequest,
  ): OpenAiClient.UnstructuredCompletionResponse {
    val response = baseClient.createUnstructuredCompletion(request = request)

    logger.logCreateUnstructuredCompletion(
        request = request,
        response = response,
    )

    return response
  }

  override suspend fun createRawStructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
  ): OpenAiClient.RawStructuredCompletionResponse {
    val response =
        baseClient.createRawStructuredCompletion(
            request = request,
            responseSchemaName = responseSchemaName,
            responseSchema = responseSchema,
        )

    logger.logCreateRawStructuredCompletion(
        request = request,
        responseSchemaName = responseSchemaName,
        responseSchema = responseSchema,
        response = response,
    )

    return response
  }

  override fun close() {
    baseClient.close()
  }
}
