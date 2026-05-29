package software.medusa.flow.core_service.worker.code_project

import kotlin.test.Test
import kotlin.test.assertEquals
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.code.CodeBlock.LineIndex
import software.medusa.commons.code.CodeBlock.LineIndexRange
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change.Patch
import software.medusa.flow.core_service.worker.code.applyChange

class CodeProject_applyPatch_tests {
  @Test
  fun test_empty() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "  }",
            "}",
        )

    val patchedContent = inputContent.applyChange(Patch.Empty)

    assertEquals(
        expected = inputContent,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_replacement_singleLine() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "  }",
            "}",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {",
            "  universe {",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 1), // "  world {"
                            endIndexExclusive = LineIndex(indexZeroBased = 2), // "  }"
                        ) to
                            Patch.Fragment(
                                CodeBlock(
                                    lines =
                                        listOf(
                                            CodeBlock.Line(content = "  universe {"),
                                        ),
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
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "  }",
            "}",
        )

    val patchBlock =
        CodeBlock.of(
            "  universe {",
            "    and all the other places too (",
            "    )",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {",
            "  universe {",
            "    and all the other places too (",
            "    )",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 1), // "  world {"
                            endIndexExclusive = LineIndex(indexZeroBased = 2), // "  }"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock,
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
  fun test_singleFragment_replacement_multipleLines() {
    val inputContent =
        TechFileContent.Code.of(
            "foo {{",
            "  bar {",
            "    baz (",
            "    )",
            "  }",
            "}}",
        )

    val patchBlock =
        CodeBlock.of(
            "  xyz [",
            "    asdf",
            "  ]",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "foo {{",
            "  xyz [",
            "    asdf",
            "  ]",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 1), // "  universe {"
                            endIndexExclusive = LineIndex(indexZeroBased = 5), // "}}"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock,
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
  fun test_singleFragment_deletion_front() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "  ]",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {{"
                            endIndexExclusive = LineIndex(indexZeroBased = 2), // "  ]"
                        ) to Patch.Fragment.Empty,
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
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {{",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 1), // "world ["
                            endIndexExclusive = LineIndex(indexZeroBased = 3), // "}}"
                        ) to Patch.Fragment.Empty,
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
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {{",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 1), // "world ["
                            endIndexExclusive = LineIndex(indexZeroBased = 4), // EOF
                        ) to Patch.Fragment.Empty,
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
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {{"
                            endIndexExclusive = LineIndex(indexZeroBased = 4), // EOF
                        ) to Patch.Fragment.Empty,
                    ),
            ),
        )

    assertEquals(
        expected = TechFileContent.Code.Empty,
        actual = patchedContent,
    )
  }

  @Test
  fun test_singleFragment_append_front() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val patchBlock =
        CodeBlock.of(
            "#!/bin/hello",
            "%include hello.lib",
            "",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "#!/bin/hello",
            "%include hello.lib",
            "",
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange.empty(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {{"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock,
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
  fun test_singleFragment_append_middle() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val patchBlock =
        CodeBlock.of(
            "// and all the other places",
            "// too",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "// and all the other places",
            "// too",
            "  ]",
            "}}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange.empty(
                            startIndex = LineIndex(indexZeroBased = 2), // "  ]"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock,
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
  fun test_singleFragment_append_rear() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
        )

    val patchBlock =
        CodeBlock.of(
            "~ end of file",
            "~ end of transmission",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world [",
            "  ]",
            "}}",
            "~ end of file",
            "~ end of transmission",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange.empty(
                            startIndex = LineIndex(indexZeroBased = 4), // EOF
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock,
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
  fun test_multipleFragments_oneToOne() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "    and all the other places too (",
            "    )",
            "  }",
            "}",
        )

    val patchBlock1 =
        CodeBlock.of(
            "hi {",
        )

    val patchBlock2 =
        CodeBlock.of(
            "    and universe [",
            "    ]",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hi {",
            "  world {",
            "    and universe [",
            "    ]",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive = LineIndex(indexZeroBased = 1), // "  world {"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock1,
                            ),
                        LineIndexRange(
                            startIndex =
                                LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                            endIndexExclusive = LineIndex(indexZeroBased = 4), // "  }"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock2,
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
  fun test_multipleFragments_collapsingOverall() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "    and all the other places too (",
            "      with greetings [",
            "      ]",
            "    )",
            "  }",
            "}",
        )

    val patchBlock1 =
        CodeBlock.of(
            "hi {{",
        )

    val patchBlock2 =
        CodeBlock.of(
            "      with hugs [{",
            "      }]",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hi {{",
            "    and all the other places too (",
            "      with hugs [{",
            "      }]",
            "    )",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive =
                                LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock1,
                            ),
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 3), // "      with greetings ["
                            endIndexExclusive = LineIndex(indexZeroBased = 5), // "    )"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock2,
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
  fun test_multipleFragments_expandingOverall() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "    and all the other places too (",
            "    )",
            "  }",
            "}",
        )

    val patchBlock1 =
        CodeBlock.of(
            "hello {{",
            "  world {",
            "    and all the known places too (",
        )

    val patchBlock2 =
        CodeBlock.of(
            "    with greetings [",
            "      and salutations",
            "    ]",
            "    )",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hello {{",
            "  world {",
            "    and all the known places too (",
            "    with greetings [",
            "      and salutations",
            "    ]",
            "    )",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    mapOf(
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive = LineIndex(indexZeroBased = 3), // "  )"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock1,
                            ),
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 3), // "  )"
                            endIndexExclusive = LineIndex(indexZeroBased = 4), // "}"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock2,
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
  fun test_multipleFragments_appliedInLineOrder_notMapInsertionOrder() {
    val inputContent =
        TechFileContent.Code.of(
            "hello {",
            "  world {",
            "    and all the other places too (",
            "    )",
            "  }",
            "}",
        )

    val patchBlock1 =
        CodeBlock.of(
            "hi {",
        )

    val patchBlock2 =
        CodeBlock.of(
            "    and universe [",
            "    ]",
        )

    val expectedContent =
        TechFileContent.Code.of(
            "hi {",
            "  world {",
            "    and universe [",
            "    ]",
            "  }",
            "}",
        )

    val patchedContent =
        inputContent.applyChange(
            Patch(
                fragmentByOldLineIndexRange =
                    linkedMapOf(
                        LineIndexRange(
                            startIndex =
                                LineIndex(
                                    indexZeroBased = 2
                                ), // "    and all the other places too ("
                            endIndexExclusive = LineIndex(indexZeroBased = 4), // "  }"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock2,
                            ),
                        LineIndexRange(
                            startIndex = LineIndex(indexZeroBased = 0), // "hello {"
                            endIndexExclusive = LineIndex(indexZeroBased = 1), // "  world {"
                        ) to
                            Patch.Fragment(
                                newCodeBlock = patchBlock1,
                            ),
                    ),
            ),
        )

    assertEquals(
        expected = expectedContent,
        actual = patchedContent,
    )
  }
}
