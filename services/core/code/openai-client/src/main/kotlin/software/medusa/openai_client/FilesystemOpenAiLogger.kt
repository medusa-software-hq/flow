package software.medusa.openai_client

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.schema.json.JsonSchema
import kotlinx.schema.json.encodeToJsonObject
import kotlinx.serialization.Serializable
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

    writeRequestFiles(
        logEntryDirectoryPath = logEntryDirectoryPath,
        request = request,
    )
    writeTextFile(
        filePath = logEntryDirectoryPath.resolve("response.txt"),
        content = response.responseText,
    )
    response.usage?.let { usage ->
      writeUsageFile(
          logEntryDirectoryPath = logEntryDirectoryPath,
          usage = usage,
      )
    }
  }

  override fun logCreateRawStructuredCompletion(
      request: OpenAiClient.CompletionRequest,
      responseSchemaName: String,
      responseSchema: JsonSchema,
      response: OpenAiClient.RawStructuredCompletionResponse,
  ) {
    val logEntryDirectoryPath =
        createLogEntryDirectoryPath(operationName = "createRawStructuredCompletion")

    writeRequestFiles(
        logEntryDirectoryPath = logEntryDirectoryPath,
        request = request,
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
    response.usage?.let { usage ->
      writeUsageFile(
          logEntryDirectoryPath = logEntryDirectoryPath,
          usage = usage,
      )
    }
  }

  private fun writeRequestFiles(
      logEntryDirectoryPath: Path,
      request: OpenAiClient.CompletionRequest,
  ) {
    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("request.json"),
        content =
            json.encodeToString(
                RequestLogEntry.serializer(),
                RequestLogEntry(model = request.model.id),
            ),
    )

    val messagesDirectoryPath = Files.createDirectories(logEntryDirectoryPath.resolve("messages"))

    request.input.messages.forEachIndexed { index, message ->
      val messageDirectoryPath =
          Files.createDirectories(messagesDirectoryPath.resolve(index.toString()))

      writeJsonFile(
          filePath = messageDirectoryPath.resolve("message.json"),
          content =
              json.encodeToString(
                  MessageLogEntry.serializer(),
                  MessageLogEntry(
                      role = message.role,
                      name = message.name,
                  ),
              ),
      )
      writeTextFile(
          filePath = messageDirectoryPath.resolve("message-content.txt"),
          content = message.text,
      )
    }
  }

  private fun writeUsageFile(
      logEntryDirectoryPath: Path,
      usage: OpenAiClient.Usage,
  ) {
    writeJsonFile(
        filePath = logEntryDirectoryPath.resolve("usage.json"),
        content = json.encodeToString(UsageLogEntry.serializer(), UsageLogEntry.from(usage)),
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

  @Serializable
  private data class SchemaLogEntry(
      val name: String,
      val schema: kotlinx.serialization.json.JsonObject,
  )

  @Serializable
  private data class RequestLogEntry(
      val model: String,
  )

  @Serializable
  private data class MessageLogEntry(
      val role: OpenAiRole,
      val name: String?,
  )

  @Serializable
  private data class UsageLogEntry(
      val promptTokenCount: Int,
      val completionTokenCount: Int,
      val totalTokenCount: Int,
  ) {
    companion object {
      fun from(usage: OpenAiClient.Usage): UsageLogEntry =
          UsageLogEntry(
              promptTokenCount = usage.promptTokenCount,
              completionTokenCount = usage.completionTokenCount,
              totalTokenCount = usage.totalTokenCount,
          )
    }
  }

  companion object {
    private val json = Json { prettyPrint = true }
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)
  }
}
