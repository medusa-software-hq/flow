package software.medusa.flow.core_service.worker.code_project

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor
import software.medusa.flow.core_service.worker.code_project.CodeProject.CodeBlock
import software.medusa.flow.core_service.worker.code_project.CodeProject.CodeFileContent

class CodeProject_applyPatch_tests {
  @Test
  fun test_empty() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent = inputContent.applyPatch(AiCodeEditor.Patch.Empty)

    assertEquals(
        expected = inputContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_replacement_singleLine() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
              }
            }
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {
              universe {
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 1), // "  world {"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 2), // "  }"
                        ) to
                            CodeBlock(
                                lines =
                                    listOf(
                                        CodeBlock.Line(content = "  universe {"),
                                    ),
                            ),
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_replacement_singleLine_expanding() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
              }
            }
            """
                .trimIndent(),
        )

    val patchBlock =
        CodeBlock.parse(
            """
            universe {
              and all the other places too (
              )
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {
              universe {
                and all the other places too (
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 1), // "  world {"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 2), // "  }"
                        ) to patchBlock,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_replacement_multipleLines() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              universe {
                and all the other places too (
                )
              }
            }}
            """
                .trimIndent(),
        )

    val patchBlock =
        CodeBlock.parse(
            """
            did you know that the universe covers everything [
              thank you woman and get on my horse
            ]
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
              did you know that the universe covers everything [
                thank you woman and get on my horse
              ]
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex =
                                AiCodeEditor.LineIndex(indexZeroBased = 1), // "  universe {"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 5), // "}}"
                        ) to patchBlock,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_deletion_front() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
              ]
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {{"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 2), // "  ]"
                        ) to CodeBlock.Empty,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_deletion_middle() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 1), // "world ["
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 3), // "}}"
                        ) to CodeBlock.Empty,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_deletion_rear() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 1), // "world ["
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 4), // EOF
                        ) to CodeBlock.Empty,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_deletion_whole() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {{"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 4), // EOF
                        ) to CodeBlock.Empty,
                    ),
            ),
        )

    assertEquals(
        expected = CodeFileContent.Empty,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_append_front() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val patchBlock =
        CodeBlock(
            lines =
                listOf(
                    CodeBlock.Line(content = "#!/bin/hello"),
                    CodeBlock.Line(content = "%include hello.lib"),
                    CodeBlock.Line.Empty,
                ),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            #!/bin/hello
            %include hello.lib

            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange.empty(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {{"
                        ) to patchBlock,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_append_middle() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val patchBlock =
        CodeBlock.parse(
            """
            // and all the other places
            // too
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
            // and all the other places
            // too
              ]
            }}
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange.empty(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 2), // "  ]"
                        ) to patchBlock,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_append_rear() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            """
                .trimIndent(),
        )

    val patchBlock =
        CodeBlock.parse(
            """
            ~ end of file
            ~ end of transmission
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
              world [
              ]
            }}
            ~ end of file
            ~ end of transmission
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange.empty(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 4), // EOF
                        ) to patchBlock,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_multipleFragments_oneToOne() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
                and all the other places too (
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchBlock1 =
        CodeBlock.parse(
            """
            hi {
            """
                .trimIndent(),
        )

    val patchBlock2 =
        CodeBlock.parse(
            """
            and universe [
            ]
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hi {
              world {
                and universe [
                ]
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive =
                                AiCodeEditor.LineIndex(indexZeroBased = 1), // "  world {"
                        ) to patchBlock1,
                        AiCodeEditor.LineIndexRange(
                            startIndex =
                                AiCodeEditor.LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 4), // "  }"
                        ) to patchBlock2,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_multipleFragments_collapsingOverall() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
                and all the other places too (
                  with greetings [
                  ]
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchBlock1 =
        CodeBlock.parse(
            """
            hi {{
            """
                .trimIndent(),
        )

    val patchBlock2 =
        CodeBlock.parse(
            """
            with hugs [{
            }]
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hi {{
                and all the other places too (
                  with hugs [{
                  }]
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive =
                                AiCodeEditor.LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                        ) to patchBlock1,
                        AiCodeEditor.LineIndexRange(
                            startIndex =
                                AiCodeEditor.LineIndex(
                                    indexZeroBased = 3
                                ), // "      with greetings ["
                            endIndexExclusive =
                                AiCodeEditor.LineIndex(indexZeroBased = 5), // "    )"
                        ) to patchBlock2,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_multipleFragments_expandingOverall() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
                and all the other places too (
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchBlock1 =
        CodeBlock.parse(
            """
            hello {{
              world {
                and all the known places too (
            """
                .trimIndent(),
        )

    val patchBlock2 =
        CodeBlock.parse(
            """
            with greetings [
              and salutations
            ]
            )
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hello {{
              world {
                and all the known places too (
                with greetings [
                  and salutations
                ]
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    mapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 3), // "  )"
                        ) to patchBlock1,
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 3), // "  )"
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 4), // "}"
                        ) to patchBlock2,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_multipleFragments_appliedInLineOrder_notMapInsertionOrder() {
    val inputContent =
        CodeFileContent.parse(
            """
            hello {
              world {
                and all the other places too (
                )
              }
            }
            """
                .trimIndent(),
        )

    val patchBlock1 =
        CodeBlock.parse(
            """
            hi {
            """
                .trimIndent(),
        )

    val patchBlock2 =
        CodeBlock.parse(
            """
            and universe [
            ]
            """
                .trimIndent(),
        )

    val expectedContent =
        CodeFileContent.parse(
            """
            hi {
              world {
                and universe [
                ]
              }
            }
            """
                .trimIndent(),
        )

    val patchedContent =
        inputContent.applyPatch(
            AiCodeEditor.Patch(
                newCodeBlockByOldLineIndexRange =
                    linkedMapOf(
                        AiCodeEditor.LineIndexRange(
                            startIndex =
                                AiCodeEditor.LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                            endIndexExclusive = AiCodeEditor.LineIndex(indexZeroBased = 4), // "  }"
                        ) to patchBlock2,
                        AiCodeEditor.LineIndexRange(
                            startIndex = AiCodeEditor.LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive =
                                AiCodeEditor.LineIndex(indexZeroBased = 1), // "  world {"
                        ) to patchBlock1,
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }
}
