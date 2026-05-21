package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeFileDiagnosis
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeModuleDiagnosis

class ProperAiCodeEditor_tests {
  private val exampleTaskDescription = "Make 'hello' louder everywhere"

  private val exampleFileName = UnixPath.Name.Literal("example.hello")
  private val exampleFilePath = LiteralRelativeUnixPath.of(exampleFileName)

  private val irrelevantCodeMasker: AiCodePatcher.CodeMasker =
      object : AiCodePatcher.CodeMasker {
        override fun prepareMask(
            codeFileContent: CodeFileContent,
        ): AiCodePatcher.MaskedCodeFileContent.Mask =
            AiCodePatcher.MaskedCodeFileContent.Mask(
                maskedLineRanges =
                    buildMaskedLineRanges(
                        codeFileContent = codeFileContent,
                    ),
            )

        private fun buildMaskedLineRanges(
            codeFileContent: CodeFileContent,
        ): Set<LineIndexRange> {
          val indexedLineIterator = codeFileContent.indexedLines.iterator()

          tailrec fun MutableSet<LineIndexRange>.buildMaskedLineRangesRecursively(
              openedRangeStartIndex: LineIndex?,
          ) {
            val (nextLineIndex, nextLine) = indexedLineIterator.nextOrNull() ?: return

            val newOpenedRangeStartIndex =
                when {
                  nextLine.content.contains("fruit-related") -> { // Line is non-interesting.
                    // Carry over the open range or open a new one
                    openedRangeStartIndex ?: nextLineIndex
                  }

                  else -> {
                    when (openedRangeStartIndex) {
                      null -> {
                        // Do nothing. Line is interesting and no range is opened.
                      }

                      else -> {
                        // The line is interesting, we need to close an opened mask range.
                        add(
                            LineIndexRange(
                                startIndex = openedRangeStartIndex,
                                endIndexExclusive = nextLineIndex,
                            ),
                        )
                      }
                    }

                    // No range is opened at this point
                    null
                  }
                }

            // Recurse
            buildMaskedLineRangesRecursively(
                openedRangeStartIndex = newOpenedRangeStartIndex,
            )
          }

          return buildSet {
            buildMaskedLineRangesRecursively(
                // Initially, no range is open
                openedRangeStartIndex = null,
            )
          }
        }
      }

  @Test
  fun test_attemptToCompleteTask_wiresPatcherMaskerAndSelector() = runTest {
    val initialExampleFileContent =
        """
        %import say

        %def greet1 = say "hello!"

        %def say_apple = say "Apple!" // fruit-related

        %def greet2 = say "hello."

        // This is fruit-related
        %def say_banana = say "Banana!" // still fruit-related

        %def greet3 = say "hello =)"
        """
            .trimIndent() // (without trailing newline)

    val workingDirectory =
        MemoryCompatFsDirectory().apply {
          createFile(
              name = exampleFileName,
              initialContent = initialExampleFileContent.encodeToByteString(),
          )
        }

    val fakeAiCodePatcher =
        object : AiCodePatcher {
          override fun patchToCompleteTask(
              taskDescription: String,
          ): AiCodePatcher.PatchGenerator =
              when (taskDescription) {
                exampleTaskDescription ->
                    buildReplacingPatchGenerator(
                        replacerByFilePath =
                            mapOf(
                                exampleFilePath to
                                    CodeReplacer(
                                        original = "hello",
                                        replacement = "HELLO",
                                    ),
                            ),
                    )

                else ->
                    throw UnsupportedOperationException(
                        "Unexpected task description: $taskDescription. Expected: $exampleTaskDescription",
                    )
              }

          override fun patchToFixIssues(
              originalTaskDescription: String,
              moduleDiagnosis: CodeModuleDiagnosis.Incorrect,
          ): AiCodePatcher.PatchGenerator =
              throw UnsupportedOperationException("Not used in this test")
        }

    val fakeAiCodeMasker =
        object : AiCodeMasker {
          override fun maskCodeForTaskCompletion(
              taskDescription: String,
          ): AiCodePatcher.CodeMasker =
              when (taskDescription) {
                exampleTaskDescription -> irrelevantCodeMasker

                else ->
                    throw UnsupportedOperationException(
                        "Unexpected task description: $taskDescription. Expected: $exampleTaskDescription",
                    )
              }

          override fun maskCodeForIssueFixing(
              originalTaskDescription: String,
              moduleDiagnosis: CodeModuleDiagnosis,
          ): AiCodePatcher.CodeMasker = throw UnsupportedOperationException("Not used in this test")
        }

    val aiCodeEditor =
        ProperAiCodeEditor(
            aiCodePatcher = fakeAiCodePatcher,
            aiCodeMasker = fakeAiCodeMasker,
        )

    aiCodeEditor
        .attemptToCompleteTask(
            relevantFilePaths = setOf(exampleFilePath),
            taskDescription = exampleTaskDescription,
        )
        .editWithin(
            workingDirectory = workingDirectory,
        )

    val expectedNewExampleFileContent =
        """
        %import say

        %def greet1 = say "HELLO!"

        %def say_apple = say "Apple!" // fruit-related

        %def greet2 = say "HELLO."

        // This is fruit-related
        %def say_banana = say "Banana!" // still fruit-related

        %def greet3 = say "HELLO =)"

        """
            .trimIndent() // (with trailing newline)

    val newFile =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                workingDirectory.extract(name = exampleFileName),
            ),
        )

    val newFileContent = newFile.read().decodeToString()

    assertEquals(
        expected = expectedNewExampleFileContent,
        actual = newFileContent,
    )
  }

  @Test
  fun test_attemptToFixIssues_wiresIssueInputs() = runTest {
    val utilsFileName = UnixPath.Name.Literal("utils.hello")
    val utilsFilePath = LiteralRelativeUnixPath.of(utilsFileName)

    // `sya` instead of `say` in two places
    // `gret3` instead of `greet3`
    val initialExampleFileContent =
        """
        %import say, shout

        %def greet1_loud = sya "HELLO!"

        %def say_apple = say "Apple!" // Fruit-related

        %def greet2_loud = shout "HELLO."

        %def say_banana = say "Banana!" // Fruit-related

        %def greet3_loud = sya "HELLO =)"
        """
            .trimIndent() // (without trailing newline)

    val initialUtilFileContent =
        """
        %import greet3

        %def greet_wrapped = greet3
        """
            .trimIndent() // (without trailing newline)

    val workingDirectory =
        MemoryCompatFsDirectory().apply {
          createFile(
              name = exampleFileName,
              initialContent = initialExampleFileContent.encodeToByteString(),
          )

          createFile(
              name = utilsFileName,
              initialContent = initialUtilFileContent.encodeToByteString(),
          )
        }

    val exampleDiagnosis =
        CodeModuleDiagnosis.Incorrect(
            diagnosisByFilePath =
                mapOf(
                    exampleFilePath to
                        CodeFileDiagnosis(
                            issues =
                                listOf(
                                    CodeFileDiagnosis.Issue("Line 3: No such function: `sya`"),
                                    CodeFileDiagnosis.Issue("Line 11: No such function: `sya`"),
                                ),
                        ),
                    utilsFilePath to
                        CodeFileDiagnosis(
                            issues =
                                listOf(
                                    CodeFileDiagnosis.Issue("Line 1: No such entity: `greet3`"),
                                    CodeFileDiagnosis.Issue("Line 3: Not a function: `greet3`"),
                                ),
                        ),
                ),
        )

    val fakeAiCodePatcher =
        object : AiCodePatcher {
          override fun patchToCompleteTask(taskDescription: String): AiCodePatcher.PatchGenerator =
              throw UnsupportedOperationException("Not used in this test")

          override fun patchToFixIssues(
              originalTaskDescription: String,
              moduleDiagnosis: CodeModuleDiagnosis.Incorrect,
          ): AiCodePatcher.PatchGenerator =
              when (originalTaskDescription) {
                exampleTaskDescription ->
                    buildReplacingPatchGenerator(
                        replacerByFilePath =
                            mapOf(
                                exampleFilePath to
                                    CodeReplacer(
                                        original = "sya",
                                        replacement = "say",
                                    ),
                                utilsFilePath to
                                    CodeReplacer(
                                        original = "greet3",
                                        replacement = "greet3_loud",
                                    ),
                            ),
                    )

                else ->
                    throw UnsupportedOperationException(
                        "Unexpected original task description: $originalTaskDescription. Expected: $exampleTaskDescription",
                    )
              }
        }

    val fakeAiCodeMasker =
        object : AiCodeMasker {
          override fun maskCodeForTaskCompletion(
              taskDescription: String,
          ): AiCodePatcher.CodeMasker = throw UnsupportedOperationException("Not used in this test")

          override fun maskCodeForIssueFixing(
              originalTaskDescription: String,
              moduleDiagnosis: CodeModuleDiagnosis,
          ): AiCodePatcher.CodeMasker =
              when (originalTaskDescription) {
                exampleTaskDescription -> irrelevantCodeMasker

                else ->
                    throw UnsupportedOperationException(
                        "Unexpected original task description: $originalTaskDescription. Expected: $exampleTaskDescription",
                    )
              }
        }

    val aiCodeEditor =
        ProperAiCodeEditor(
            aiCodePatcher = fakeAiCodePatcher,
            aiCodeMasker = fakeAiCodeMasker,
        )

    aiCodeEditor
        .attemptToFixIssues(
            originalRelevantFilePaths = setOf(exampleFilePath),
            originalTaskDescription = exampleTaskDescription,
            moduleDiagnosis = exampleDiagnosis,
        )
        .editWithin(
            workingDirectory = workingDirectory,
        )

    val expectedNewExampleFileContent =
        """
        %import say, shout

        %def greet1_loud = say "HELLO!"

        %def say_apple = say "Apple!" // Fruit-related

        %def greet2_loud = shout "HELLO."

        %def say_banana = say "Banana!" // Fruit-related

        %def greet3_loud = say "HELLO =)"

        """
            .trimIndent() // (with trailing newline)

    val expectedNewUtilFileContent =
        """
        %import greet3_loud

        %def greet_wrapped = greet3_loud

        """
            .trimIndent() // (with trailing newline)

    val newExampleFile =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                workingDirectory.extract(name = exampleFileName),
            ),
        )

    val newExampleFileContent = newExampleFile.read().decodeToString()

    assertEquals(
        expected = expectedNewExampleFileContent,
        actual = newExampleFileContent,
    )

    val newUtilFile =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                workingDirectory.extract(name = utilsFileName),
            ),
        )

    val newUtilFileContent = newUtilFile.read().decodeToString()

    assertEquals(
        expected = expectedNewUtilFileContent,
        actual = newUtilFileContent,
    )
  }

  private class CodeReplacer(
      private val original: String,
      private val replacement: String,
  ) {
    fun changeByReplacing(
        codeFileContent: CodeFileContent,
    ): AiCodePatcher.ChangeSet.Change.Patch =
        AiCodePatcher.ChangeSet.Change.Patch(
            codeFileContent.indexedLines
                .mapNotNull { (lineIndex, line) ->
                  when {
                    line.content.contains(original) -> {
                      val lineIndexRange =
                          LineIndexRange(
                              startIndex = lineIndex,
                              endIndexExclusive = lineIndex.next,
                          )

                      lineIndexRange to
                          AiCodePatcher.ChangeSet.Change.Patch.Fragment(
                              newCodeBlock =
                                  CodeBlock.of(
                                      line.content.replace(original, replacement),
                                  ),
                          )
                    }

                    else -> null
                  }
                }
                .toMap(),
        )
  }

  private fun buildReplacingPatchGenerator(
      replacerByFilePath: Map<LiteralRelativeUnixPath, CodeReplacer>,
  ): AiCodePatcher.PatchGenerator =
      object : AiCodePatcher.PatchGenerator {
        override suspend fun generateChanges(
            maskedCodeCatalog: AiCodePatcher.MaskedCodeCatalog,
        ): AiCodePatcher.ChangeSet =
            AiCodePatcher.ChangeSet(
                changeByFilePath =
                    maskedCodeCatalog.maskedCodeFileContentByPath
                        .mapNotNull { (filePath, maskedCodeFileContent) ->
                          val dedicatedReplacer =
                              replacerByFilePath[filePath] ?: return@mapNotNull null

                          filePath to
                              dedicatedReplacer.changeByReplacing(
                                  codeFileContent = maskedCodeFileContent.codeFileContent,
                              )
                        }
                        .toMap(),
            )
      }
}

private fun <T> Iterator<T>.nextOrNull(): T? = if (hasNext()) next() else null
