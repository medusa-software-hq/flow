package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code_project.CodeTool

class ProperAiCodeEngineer_tests {
  @Test
  fun test_solveProblem_invokesSingleTaskAttempt() = runTest {
    val filePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("foo.kt"),
        )

    var receivedTaskDescription: String? = null
    var receivedRelevantFilePaths: Set<LiteralRelativeUnixPath>? = null
    var editCallCount = 0

    val aiCodeEditor =
        object : AiCodeEditor {
          override suspend fun attemptToCompleteTask(
              relevantFilePaths: Set<LiteralRelativeUnixPath>,
              taskDescription: String,
          ): AiCodeEditor.FileEditor {
            receivedRelevantFilePaths = relevantFilePaths
            receivedTaskDescription = taskDescription

            return object : AiCodeEditor.FileEditor {
              override suspend fun editWithin(workingDirectory: MutableCompatFsDirectory) {
                editCallCount += 1
              }
            }
          }

          override suspend fun attemptToFixIssues(
              originalRelevantFilePaths: Set<LiteralRelativeUnixPath>,
              originalTaskDescription: String,
              moduleDiagnosis: CodeTool.CodeModuleDiagnosis.Incorrect,
          ): AiCodeEditor.FileEditor = error("Not used in this test")
        }

    val aiCodeEngineer = ProperAiCodeEngineer(aiCodeEditor = aiCodeEditor)

    aiCodeEngineer.solveProblem(
        codeProject = FakeCodeProject(),
        problemStatement =
            AiCodeEngineer.ProblemStatement(
                statement = CodeBlock.of("Solve the problem."),
            ),
        problemScope =
            AiCodeEngineer.ProblemScope(
                relevantFilePaths = setOf(filePath),
            ),
    )

    assertEquals(setOf(filePath), receivedRelevantFilePaths)
    assertEquals("Solve the problem.\n", receivedTaskDescription)
    assertEquals(1, editCallCount)
  }
}
