package software.medusa.openai_client

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.schema.json.JsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FilesystemOpenAiLogger_tests {
  @Test
  fun test_logCreateUnstructuredCompletion_writesRequestAndResponseFiles() {
    val logDirectoryPath = createTempDirectory(prefix = "openai-logger-")

    try {
      val logger =
          FilesystemOpenAiLogger(
              logDirectoryPath = logDirectoryPath,
              clock = fixedClock,
          )

      logger.logCreateUnstructuredCompletion(
          request = exampleRequest,
          response = OpenAiClient.UnstructuredCompletionResponse(responseText = "response text"),
      )

      val entryDirectoryPath =
          logDirectoryPath.resolve("20260519-133000-123-createUnstructuredCompletion")
      val requestJson =
          Json.parseToJsonElement(Files.readString(entryDirectoryPath.resolve("request.json")))
              as JsonObject
      val messageDirectoryPath = entryDirectoryPath.resolve("messages").resolve("0")

      assertTrue(Files.isDirectory(entryDirectoryPath))
      assertEquals(
          expected = "gpt-5.4",
          actual = requestJson.getValue("model").jsonPrimitive.content,
      )
      assertTrue(Files.isDirectory(messageDirectoryPath))
      val messageJson =
          Json.parseToJsonElement(Files.readString(messageDirectoryPath.resolve("message.json")))
              as JsonObject
      assertEquals(
          expected = "User",
          actual = messageJson.getValue("role").jsonPrimitive.content,
      )
      assertEquals(
          expected = "hello",
          actual = Files.readString(messageDirectoryPath.resolve("message-content.txt")),
      )
      assertEquals(
          expected = "response text",
          actual = Files.readString(entryDirectoryPath.resolve("response.txt")),
      )
    } finally {
      logDirectoryPath.toFile().deleteRecursively()
    }
  }

  @Test
  fun test_logCreateRawStructuredCompletion_writesRequestSchemaAndResponseFiles() {
    val logDirectoryPath = createTempDirectory(prefix = "openai-logger-")

    try {
      val logger =
          FilesystemOpenAiLogger(
              logDirectoryPath = logDirectoryPath,
              clock = fixedClock,
          )

      logger.logCreateRawStructuredCompletion(
          request = exampleRequest,
          responseSchemaName = "response_schema",
          responseSchema = exampleSchema,
          response =
              OpenAiClient.RawStructuredCompletionResponse(
                  responseJsonElement = buildJsonObject { put("answer", 42) },
              ),
      )

      val entryDirectoryPath =
          logDirectoryPath.resolve("20260519-133000-123-createRawStructuredCompletion")
      val requestJson =
          Json.parseToJsonElement(Files.readString(entryDirectoryPath.resolve("request.json")))
              as JsonObject
      val messageDirectoryPath = entryDirectoryPath.resolve("messages").resolve("0")

      assertTrue(Files.isDirectory(entryDirectoryPath))
      assertEquals(
          expected = "gpt-5.4",
          actual = requestJson.getValue("model").jsonPrimitive.content,
      )
      assertTrue(Files.isDirectory(messageDirectoryPath))
      assertEquals(
          expected = "hello",
          actual = Files.readString(messageDirectoryPath.resolve("message-content.txt")),
      )

      val schemaJson =
          Json.parseToJsonElement(Files.readString(entryDirectoryPath.resolve("schema.json")))
              as JsonObject

      assertEquals(
          expected = "response_schema",
          actual = schemaJson.getValue("name").jsonPrimitive.content,
      )
      schemaJson.getValue("schema").jsonObject
      assertTrue(Files.exists(entryDirectoryPath.resolve("response.json")))
    } finally {
      logDirectoryPath.toFile().deleteRecursively()
    }
  }

  companion object {
    private val fixedClock: Clock =
        Clock.fixed(
            Instant.parse("2026-05-19T13:30:00.123Z"),
            ZoneOffset.UTC,
        )

    private val exampleRequest =
        OpenAiClient.CompletionRequest(
            input =
                OpenAiChat(
                    messages = listOf(OpenAiMessage(role = OpenAiRole.User, text = "hello")),
                ),
            model = OpenAiModel.GptMidi,
        )

    private val exampleSchema = JsonSchema()
  }
}
