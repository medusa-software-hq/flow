package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.code.CodeBlock
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.extractDeepMutable
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code_project.CodeModule
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeFileDiagnosis
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeModuleDiagnosis

class ProperAiCodeEngineer_tests {
  companion object {
    private const val taskDescriptionText = "Greet properly."
    private const val formattingIssueDescription = "Line 1: Unexpected semicolon"

    private const val verificationIssueDescription =
        "Line 1: Greeting must end with exclamation mark"
  }

  @Test
  fun test_solveProblem_invokesSingleTaskAttempt() = runTest {
    val fileName = UnixPath.Name.Literal("example.hello")
    val filePath = RelativeUnixPath.of(fileName)
    val initialFileContent = "%def greet = /* TODO */"
    val expectedFinalFileContent = "%def greet = say 'hello!'\n"
    val expectedTaskDescription = CodeBlock.of(taskDescriptionText).dump()

    val codeRootDirectory =
        MemoryCompatFsDirectory().apply {
          createFile(
              name = fileName,
              initialContent = initialFileContent.encodeToByteString(),
          )
        }

    val fakeAiCodeEditor =
        object : AiCodeEditor {
          override suspend fun attemptToCompleteTask(
              relevantFilePaths: Set<LiteralRelativeUnixPath>,
              taskDescription: String,
          ): AiCodeEditor.FileEditor =
              object : AiCodeEditor.FileEditor {
                override suspend fun editWithin(
                    workingDirectory: MutableCompatFsDirectory,
                ) {
                  requireRelevantFilePath(
                      relevantFilePaths = relevantFilePaths,
                      expectedFilePath = filePath,
                  )
                  requireExpectedTaskDescription(
                      actualTaskDescription = taskDescription,
                      expectedTaskDescription = expectedTaskDescription,
                  )

                  updateFile(
                      workingDirectory = workingDirectory,
                      filePath = filePath,
                  ) {
                    "%def greet = say 'hello'\n" // No exclamation mark (incorrect), no semicolon
                    // (correct)
                  }
                }
              }

          override suspend fun attemptToFixIssues(
              originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
              originalTaskDescription: String,
              moduleDiagnosis: CodeModuleDiagnosis.Incorrect,
          ): AiCodeEditor.FileEditor =
              object : AiCodeEditor.FileEditor {
                override suspend fun editWithin(
                    workingDirectory: MutableCompatFsDirectory,
                ) {
                  requireRelevantFilePath(
                      relevantFilePaths = originalRelevantFilePaths,
                      expectedFilePath = filePath,
                  )
                  requireExpectedTaskDescription(
                      actualTaskDescription = originalTaskDescription,
                      expectedTaskDescription = expectedTaskDescription,
                  )

                  val issueDescription =
                      moduleDiagnosis.requireSingleIssueDescription(
                          expectedFilePath = filePath,
                      )

                  when (issueDescription) {
                    formattingIssueDescription ->
                        updateFile(
                            workingDirectory = workingDirectory,
                            filePath = filePath,
                        ) { oldContent ->
                          oldContent.replace(";", "")
                        }

                    verificationIssueDescription ->
                        updateFile(
                            workingDirectory = workingDirectory,
                            filePath = filePath,
                        ) { oldContent ->
                          "${
                          oldContent.replace(
                              "hello",
                              "hello!",
                          )
                        };" // Added exclamation mark (correct), semicolon included (incorrect)
                        }

                    else ->
                        throw UnsupportedOperationException(
                            "Unexpected issue description: $issueDescription",
                        )
                  }
                }
              }
        }

    val aiCodeEngineer = ProperAiCodeEngineer(aiCodeEditor = fakeAiCodeEditor)

    aiCodeEngineer.solveProblem(
        codeProject =
            FakeCodeProject(
                rootModule =
                    object : CodeModule {
                      override val formattingTool: CodeTool =
                          FakeFormattingCodeTool(
                              codeRootDirectory = codeRootDirectory,
                              filePath = filePath,
                          )

                      override val verificationTool: CodeTool =
                          FakeVerificationCodeTool(
                              codeRootDirectory = codeRootDirectory,
                              filePath = filePath,
                          )
                    },
            ),
        codeRootDirectory = codeRootDirectory,
        problemStatement =
            AiCodeEngineer.ProblemStatement(
                statement = CodeBlock.of(taskDescriptionText),
            ),
        problemScope =
            AiCodeEngineer.ProblemScope(
                relevantFilePaths = setOf(filePath),
            ),
    )

    val finalFile =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                codeRootDirectory.extract(name = fileName),
            ),
        )

    assertEquals(
        expected = expectedFinalFileContent,
        actual = finalFile.read().decodeToString(),
    )
  }

  private abstract class AbstractFakeCodeTool(
      private val codeRootDirectory: MutableCompatFsDirectory,
      private val filePath: LiteralRelativeUnixPath,
      private val issueDescription: String,
  ) : CodeTool {
    protected abstract fun hasIssue(
        content: String,
    ): Boolean

    override suspend fun diagnose(): CodeModuleDiagnosis {
      val file = codeRootDirectory.requireMutableFile(filePath)

      val content = file.read().decodeToString()

      return when {
        hasIssue(content) ->
            CodeModuleDiagnosis.Incorrect(
                diagnosisByFilePath =
                    mapOf(
                        filePath to
                            CodeFileDiagnosis(
                                issues = listOf(CodeFileDiagnosis.Issue(issueDescription)),
                            ),
                    ),
            )

        else -> CodeModuleDiagnosis.Correct
      }
    }
  }

  private class FakeFormattingCodeTool(
      codeRootDirectory: MutableCompatFsDirectory,
      filePath: LiteralRelativeUnixPath,
  ) :
      AbstractFakeCodeTool(
          codeRootDirectory = codeRootDirectory,
          filePath = filePath,
          issueDescription = formattingIssueDescription,
      ) {
    override fun hasIssue(content: String): Boolean = content.contains(';')
  }

  private class FakeVerificationCodeTool(
      codeRootDirectory: MutableCompatFsDirectory,
      filePath: LiteralRelativeUnixPath,
  ) :
      AbstractFakeCodeTool(
          codeRootDirectory = codeRootDirectory,
          filePath = filePath,
          issueDescription = verificationIssueDescription,
      ) {
    override fun hasIssue(content: String): Boolean =
        content.contains("hello") && !content.contains("hello!")
  }

  private suspend fun updateFile(
      workingDirectory: MutableCompatFsDirectory,
      filePath: LiteralRelativeUnixPath,
      update: (String) -> String,
  ) {
    val file = workingDirectory.requireMutableFile(filePath)

    val oldContent = file.read().decodeToString()
    val newContent = update(oldContent)

    file.write(newContent.encodeToByteString())
  }

  private fun requireRelevantFilePath(
      relevantFilePaths: Set<LiteralRelativeUnixPath>,
      expectedFilePath: LiteralRelativeUnixPath,
  ) {
    if (expectedFilePath !in relevantFilePaths) {
      throw UnsupportedOperationException(
          "Expected relevant file paths to include ${expectedFilePath.toUnixRelativePathString()}, got: $relevantFilePaths",
      )
    }
  }

  private fun requireExpectedTaskDescription(
      actualTaskDescription: String,
      expectedTaskDescription: String,
  ) {
    if (actualTaskDescription != expectedTaskDescription) {
      throw UnsupportedOperationException(
          "Unexpected task description: $actualTaskDescription. Expected: $expectedTaskDescription",
      )
    }
  }

  private fun CodeModuleDiagnosis.Incorrect.requireSingleIssueDescription(
      expectedFilePath: LiteralRelativeUnixPath,
  ): String {
    val diagnosisEntry =
        diagnosisByFilePath.entries.singleOrNull()
            ?: throw UnsupportedOperationException(
                "Expected exactly one diagnosed file, got: ${diagnosisByFilePath.keys}",
            )

    if (diagnosisEntry.key != expectedFilePath) {
      throw UnsupportedOperationException(
          "Unexpected diagnosed file: ${diagnosisEntry.key.toUnixRelativePathString()}. Expected: ${expectedFilePath.toUnixRelativePathString()}",
      )
    }

    return diagnosisEntry.value.issues.singleOrNull()?.description
        ?: throw UnsupportedOperationException(
            "Expected exactly one issue for ${expectedFilePath.toUnixRelativePathString()}, got: ${diagnosisEntry.value.issues}",
        )
  }
}

private suspend fun MutableCompatFsDirectory.requireMutableFile(
    filePath: LiteralRelativeUnixPath,
): MutableCompatFsFile =
    when (val entity = extractDeepMutable(filePath)) {
      is MutableCompatFsFile -> entity
      null -> throw IllegalStateException("Expected file at ${filePath.toUnixRelativePathString()}")
      else ->
          throw IllegalStateException(
              "Expected file at ${filePath.toUnixRelativePathString()}, but found a directory"
          )
    }
