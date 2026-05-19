package software.medusa.openai_client

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.encodeToJsonObject
import kotlinx.serialization.json.Json

class FilesystemOpenAiLogger(
    private val logDirectoryPath: Path,
    private val clock: Clock,
) : OpenAiLogger {
  override fun logCreateUnstructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      response: OpenAiClient.UnstructuredCompletionResponse,
  ) {
    val logEntryDirectoryPath =
        createLogEntryDirectoryPath(operationName = "createUnstructuredCompletion")

    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("request.json"),
        content = json.encodeToString(OpenAiClient.CompletionRequest.serializer(), request),
    )
    writeTextFile(
        filePath = logEntryDirectoryPath.resolve("response.txt"),
        content = response.responseText,
    )
  }

  override fun logCreateRawStructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
      response: OpenAiClient.RawStructuredCompletionResponse,
  ) {
    val logEntryDirectoryPath =
        createLogEntryDirectoryPath(operationName = "createRawStructuredCompletion")

    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("request.json"),
        content = json.encodeToString(OpenAiClient.CompletionRequest.serializer(), request),
    )
    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("schema.json"),
        content =
            json.encodeToString(
                SchemaLogEntry.serializer(),
                SchemaLogEntry(
                    name = responseSchemaName,
                    schema = responseSchema.encodeToJsonObject(),
                ),
            ),
    )
    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("response.json"),
        content = json.encodeToString(response.responseJsonElement),
    )
  }

  private fun createLogEntryDirectoryPath(
      operationName: String,
  ): Path {
    Files.createDirectories(logDirectoryPath)

    val timestamp = timestampFormatter.format(clock.instant())
    val directoryPath = logDirectoryPath.resolve("$timestamp-$operationName")

    Files.createDirectories(directoryPath)

    return directoryPath
  }

  private fun writeJsonFile(
      filePath: Path,
      content: String,
  ) {
    Files.writeString(filePath, "$content\n")
  }

  private fun writeTextFile(
      filePath: Path,
      content: String,
  ) {
    Files.writeString(filePath, content)
  }

  @kotlinx.serialization.Serializable
  private data class SchemaLogEntry(
      val name: String,
      val schema: kotlinx.serialization.json.JsonObject,
  )

  companion object {
    private val json = Json { prettyPrint = true }
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
  }
}
