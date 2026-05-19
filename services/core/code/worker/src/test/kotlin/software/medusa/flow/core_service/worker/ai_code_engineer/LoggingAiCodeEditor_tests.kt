package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.bytestring.decodeToString
import kotlinx.io.bytestring.encodeToByteString
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.MutableCompatFsFile
import software.medusa.commons.filesystem.compat.extractDeepMutable
import software.medusa.commons.filesystem.compat.impl.memory.MemoryCompatFsDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool

class LoggingAiCodeEditor_tests {
  @Test
  fun test_attemptToCompleteTask_logsMetadataAndFilesystemOperations() = runTest {
    val logDirectory = MemoryCompatFsDirectory()
    val workingDirectory = MemoryCompatFsDirectory()
    val targetFilePath = LiteralRelativeUnixPath.of(UnixPath.Name.Literal("example.txt"))

    val logger =
        FilesystemAiCodeEditorLogger(
            logDirectory = logDirectory,
            clock = fixedClock,
        )

    val aiCodeEditor =
        LoggingAiCodeEditor(
            baseAiCodeEditor =
                object : AiCodeEditor {
                  override suspend fun attemptToCompleteTask(
                      relevantFilePaths: Set<LiteralRelativeUnixPath>,
                      taskDescription: String,
                  ): AiCodeEditor.FileEditor =
                      object : AiCodeEditor.FileEditor {
                        override suspend fun editWithin(
                            workingDirectory: MutableCompatFsDirectory
                        ) {
                          workingDirectory.createFile(
                              name = UnixPath.Name.Literal("example.txt"),
                              initialContent = "hello".encodeToByteString(),
                          )
                        }
                      }

                  override suspend fun attemptToFixIssues(
                      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
                      originalTaskDescription: String,
                      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
                  ): AiCodeEditor.FileEditor = error("Not used in this test")
                },
            logger = logger,
        )

    aiCodeEditor
        .attemptToCompleteTask(
            relevantFilePaths = setOf(targetFilePath),
            taskDescription = "Do the thing",
        )
        .editWithin(workingDirectory = workingDirectory)

    assertTextFile(
        rootDirectory = logDirectory,
        relativePath = "1000-attemptToCompleteTask/taskDescription.txt",
        expectedContent = "Do the thing",
    )
    assertTextFileContains(
        rootDirectory = logDirectory,
        relativePath = "1000-attemptToCompleteTask/relevantFilePaths.json",
        expectedSubstring = "example.txt",
    )
    assertBinaryFile(
        rootDirectory = logDirectory,
        relativePath =
            "1000-attemptToCompleteTask/working-directory/sub/example.txt/1000-write/writtenContent.bin",
        expectedContent = "hello".encodeToByteString(),
    )
  }

  @Test
  fun test_attemptToFixIssues_logsMetadataAndFilesystemOperations() = runTest {
    val logDirectory = MemoryCompatFsDirectory()
    val workingDirectory =
        MemoryCompatFsDirectory().apply {
          createFile(
              name = UnixPath.Name.Literal("example.txt"),
              initialContent = "before".encodeToByteString(),
          )
        }

    val logger =
        FilesystemAiCodeEditorLogger(
            logDirectory = logDirectory,
            clock = fixedClock,
        )

    val diagnosis =
        CodeTool.CodeModuleDiagnosis.Incorrect(
            diagnosisByFilePath =
                mapOf(
                    LiteralRelativeUnixPath.of(UnixPath.Name.Literal("example.txt")) to
                        CodeTool.CodeFileDiagnosis(
                            issues = listOf(CodeTool.CodeFileDiagnosis.Issue("Broken thing")),
                        ),
                ),
        )

    val aiCodeEditor =
        LoggingAiCodeEditor(
            baseAiCodeEditor =
                object : AiCodeEditor {
                  override suspend fun attemptToCompleteTask(
                      relevantFilePaths: Set<LiteralRelativeUnixPath>,
                      taskDescription: String,
                  ): AiCodeEditor.FileEditor = error("Not used in this test")

                  override suspend fun attemptToFixIssues(
                      originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
                      originalTaskDescription: String,
                      moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
                  ): AiCodeEditor.FileEditor =
                      object : AiCodeEditor.FileEditor {
                        override suspend fun editWithin(
                            workingDirectory: MutableCompatFsDirectory
                        ) {
                          val file =
                              assertNotNull(
                                  assertIs<MutableCompatFsFile>(
                                      workingDirectory.extractDeepMutable(
                                          LiteralRelativeUnixPath.of(
                                              UnixPath.Name.Literal("example.txt")
                                          ),
                                      ),
                                  ),
                              )

                          file.write("after".encodeToByteString())
                        }
                      }
                },
            logger = logger,
        )

    aiCodeEditor
        .attemptToFixIssues(
            originalRelevantFilePaths =
                setOf(LiteralRelativeUnixPath.of(UnixPath.Name.Literal("example.txt"))),
            originalTaskDescription = "Fix the thing",
            moduleDiagnosis = diagnosis,
        )
        .editWithin(workingDirectory = workingDirectory)

    assertTextFile(
        rootDirectory = logDirectory,
        relativePath = "1000-attemptToFixIssues/originalTaskDescription.txt",
        expectedContent = "Fix the thing",
    )
    assertTextFileContains(
        rootDirectory = logDirectory,
        relativePath = "1000-attemptToFixIssues/originalRelevantFilePaths.json",
        expectedSubstring = "example.txt",
    )
    assertTextFileContains(
        rootDirectory = logDirectory,
        relativePath = "1000-attemptToFixIssues/moduleDiagnosis.json",
        expectedSubstring = "Broken thing",
    )
    assertBinaryFile(
        rootDirectory = logDirectory,
        relativePath =
            "1000-attemptToFixIssues/working-directory/sub/example.txt/1000-write/writtenContent.bin",
        expectedContent = "after".encodeToByteString(),
    )
  }

  private suspend fun assertTextFile(
      rootDirectory: MutableCompatFsDirectory,
      relativePath: String,
      expectedContent: String,
  ) {
    val file =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                rootDirectory.extractDeepMutable(parseLiteralRelativePath(relativePath)),
            ),
        )

    assertEquals(expected = expectedContent, actual = file.read().decodeToString())
  }

  private suspend fun assertTextFileContains(
      rootDirectory: MutableCompatFsDirectory,
      relativePath: String,
      expectedSubstring: String,
  ) {
    val file =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                rootDirectory.extractDeepMutable(parseLiteralRelativePath(relativePath)),
            ),
        )

    kotlin.test.assertTrue(file.read().decodeToString().contains(expectedSubstring))
  }

  private suspend fun assertBinaryFile(
      rootDirectory: MutableCompatFsDirectory,
      relativePath: String,
      expectedContent: kotlinx.io.bytestring.ByteString,
  ) {
    val file =
        assertNotNull(
            assertIs<MutableCompatFsFile>(
                rootDirectory.extractDeepMutable(parseLiteralRelativePath(relativePath)),
            ),
        )

    assertEquals(expected = expectedContent, actual = file.read())
  }

  companion object {
    private val fixedClock: java.time.Clock =
        java.time.Clock.fixed(java.time.Instant.ofEpochMilli(1000), java.time.ZoneOffset.UTC)
  }
}

private fun parseLiteralRelativePath(
    relativePath: String,
): LiteralRelativeUnixPath =
    RelativeUnixPath.parse(relativePath).toLiteral()
        ?: error("Expected literal relative path: $relativePath")
