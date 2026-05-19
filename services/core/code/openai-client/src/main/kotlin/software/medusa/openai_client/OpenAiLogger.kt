package software.medusa.openai_client

import kotlinx.schema.json.JsonSchema

interface OpenAiLogger {
  fun logCreateUnstructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      response: OpenAiClient.UnstructuredCompletionResponse,
  )

  fun logCreateRawStructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
      response: OpenAiClient.RawStructuredCompletionResponse,
  )
}
