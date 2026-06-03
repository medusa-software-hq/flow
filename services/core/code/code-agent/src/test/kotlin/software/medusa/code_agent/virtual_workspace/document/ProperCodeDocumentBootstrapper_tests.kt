package software.medusa.code_agent.virtual_workspace.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient

class ProperCodeDocumentBootstrapper_tests {
  @Test
  fun test_bootstrapDocument_fills_summaries_and_expansion_states() = runTest {
    val openAiClient =
        FakeOpenAiClient(
            rawStructuredResponse =
                Json.parseToJsonElement(
                    """
                    {
                      "regions": [
                        {
                          "path": "Primary Foo functionality",
                          "summary": "Contains the main Foo behavior that readers should inspect first.",
                          "shouldExpand": true
                        },
                        {
                          "path": "Rarely used utility extensions",
                          "summary": "Keeps secondary extension helpers that are not central to the main file flow.",
                          "shouldExpand": false
                        }
                      ]
                    }
                    """
                        .trimIndent(),
                ),
        )

    val document =
        CodeDocument.load(
            language = CodeLanguage.Kotlin,
            content =
                software.medusa.commons.filesystem.tech.TechFileContent.Code.of(
                    "class Foo {",
                    "  //#region Primary Foo functionality",
                    "  fun run() = Unit",
                    "  //#endregion",
                    "",
                    "  //#region Rarely used utility extensions",
                    "  fun String.debugFoo() = this",
                    "  //#endregion",
                    "}",
                ),
        )

    val bootstrappedDocument =
        ProperCodeDocumentBootstrapper(openAiClient = openAiClient).bootstrapDocument(document)

    assertEquals(
        expected =
            listOf(
                "Primary Foo functionality",
                "Rarely used utility extensions",
            ),
        actual = openAiClient.lastRequestRegionPaths,
    )

    val regions = bootstrappedDocument.rootContainer.nodes.filterIsInstance<CodeNode.Region>()

    assertEquals(CodeNode.Region.State.Expanded, regions[0].state)
    assertEquals(
        "Contains the main Foo behavior that readers should inspect first.",
        regions[0].summary?.paragraph?.inlineContent?.singleText(),
    )

    assertEquals(CodeNode.Region.State.Collapsed, regions[1].state)
    assertEquals(
        "Keeps secondary extension helpers that are not central to the main file flow.",
        regions[1].summary?.paragraph?.inlineContent?.singleText(),
    )
  }

  private class FakeOpenAiClient(
      private val rawStructuredResponse: JsonElement,
  ) : OpenAiClient {
    var lastRequestRegionPaths: List<String> = emptyList()
      private set

    override suspend fun createUnstructuredCompletion(
        request: OpenAiClient.CompletionRequest,
    ): OpenAiClient.UnstructuredCompletionResponse {
      throw UnsupportedOperationException("Unexpected unstructured completion request")
    }

    override suspend fun createRawStructuredCompletion(
        request: OpenAiClient.CompletionRequest,
        responseSchemaName: String,
        responseSchema: kotlinx.schema.json.JsonSchema,
    ): OpenAiClient.RawStructuredCompletionResponse {
      lastRequestRegionPaths = extractRegionPaths(request.input)

      return OpenAiClient.RawStructuredCompletionResponse(
          responseJsonElement = rawStructuredResponse,
          usage = null,
      )
    }

    override fun close() = Unit

    private fun extractRegionPaths(chat: OpenAiChat): List<String> {
      val text = chat.messages.last().text
      val marker = "Region paths:\n"
      val startIndex = text.indexOf(marker)
      require(startIndex >= 0) { "Missing region path section in prompt" }

      return text
          .substring(startIndex + marker.length)
          .lineSequence()
          .filter { it.startsWith("- ") }
          .map { it.removePrefix("- ") }
          .toList()
    }
  }

  private fun List<software.medusa.markdown.MarkdownInline>.singleText(): String =
      (single() as software.medusa.markdown.MarkdownInline.Text).text
}
