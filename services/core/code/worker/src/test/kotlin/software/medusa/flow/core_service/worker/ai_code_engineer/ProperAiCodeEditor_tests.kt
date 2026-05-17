package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.schema.json.JsonSchema
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionInstructions
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionScope
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.Patch
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.PatchSet
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiClient.RawStructuredCompletionResponse
import software.medusa.openai_client.OpenAiClient.UnstructuredCompletionResponse

class ProperAiCodeEditor_tests {
  @Test
  fun test_basicEdition() = runTest {
    val mockOpenAiClient =
        object : OpenAiClient {
          override suspend fun createUnstructuredCompletion(
              request: OpenAiClient.CompletionRequest,
          ): UnstructuredCompletionResponse {
            throw NotImplementedError()
          }

          override suspend fun createRawStructuredCompletion(
              request: OpenAiClient.CompletionRequest,
              responseSchemaName: String,
              responseSchema: JsonSchema,
          ): RawStructuredCompletionResponse =
              RawStructuredCompletionResponse(
                  responseJsonElement =
                      buildJsonObject {
                        put(
                            "patches",
                            buildJsonArray {
                              add(
                                  buildJsonObject {
                                    put(
                                        "filePath",
                                        "foo/bar/baz.hello",
                                    )

                                    put(
                                        "fragments",
                                        buildJsonArray {
                                          add(
                                              buildJsonObject {
                                                put("start", 3)
                                                put("endExclusive", 4)
                                                put(
                                                    "content",
                                                    "%def say_hello [] => %say 'HELLO!!!'\n",
                                                )
                                              },
                                          )
                                        },
                                    )
                                  },
                              )
                            },
                        )
                      },
              )

          override fun close() {
            throw NotImplementedError()
          }
        }

    val aiCodeEditor =
        ProperAiCodeEditor(
            openAiClient = mockOpenAiClient,
        )

    val bazFilePath: LiteralRelativeUnixPath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("foo"),
            UnixPath.Name.Literal("bar"),
            UnixPath.Name.Literal("baz.hello"),
        )

    val generatedPatchSet =
        aiCodeEditor.generateEditionPatchSet(
            editionInstructions =
                EditionInstructions(
                    instructions =
                        CodeBlock.of(
                            "Make the `say_hello` function say `HELLO!!!`.",
                        ),
                ),
            editionScope =
                EditionScope(
                    maskedCodeFileContentByPath =
                        mapOf(
                            bazFilePath to
                                MaskedCodeFileContent(
                                    blocks =
                                        listOf(
                                            MaskedCodeFileContent.ContentBlock(
                                                startIndex = LineIndex.ofOneBased(1),
                                                content =
                                                    CodeBlock.of(
                                                        /* 1 */ "#!/bin/hello",
                                                        /* 2 */ "",
                                                        /* 3 */ "%def say_hello [] => %say 'Hello.'",
                                                        /* 4 */ "",
                                                        /* 5 */ "%def say_bye [] => %say 'Bye.'",
                                                        /* 6 */ "",
                                                    ),
                                            ),
                                            MaskedCodeFileContent.MaskBlock(
                                                summary =
                                                    CodeBlock.of(
                                                        "A few irrelevant function definitions",
                                                    ),
                                            ),
                                            MaskedCodeFileContent.ContentBlock(
                                                startIndex = LineIndex.ofOneBased(32),
                                                content =
                                                    CodeBlock.of(
                                                        /* 32 */ "",
                                                        /* 33 */ "%def main [] => say_hello[]",
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                ),
        )

    assertEquals(
        expected =
            PatchSet(
                patchByFilePath =
                    mapOf(
                        bazFilePath to
                            Patch(
                                fragmentByOldLineIndexRange =
                                    mapOf(
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(3),
                                            endIndexExclusive = LineIndex.ofOneBased(4),
                                        ) to
                                            Patch.Fragment(
                                                CodeBlock.of(
                                                    "%def say_hello [] => %say 'HELLO!!!'",
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            ),
        actual = generatedPatchSet,
    )
  }
}
