package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.FileEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer.ProblemScope
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeModuleDiagnosis

class ProperAiCodeEngineer(
    private val aiCodeEditor: AiCodeEditor,
) : AiCodeEngineer {
  private class ProblemSolvingContext(
      private val aiCodeEditor: AiCodeEditor,
      private val codeRootDirectory: MutableCompatFsDirectory,
      private val codeProject: CodeProject,
      private val problemStatement: AiCodeEngineer.ProblemStatement,
      private val problemScope: ProblemScope,
  ) {
    companion object {
      private const val maxIterationCount = 8
    }

    /** Count of started iterations. */
    private var iterationCount = 0

    private suspend fun FileEditor.editWithinWorktree() {
      editWithin(
          workingDirectory = codeRootDirectory,
      )
    }

    suspend fun solveProblemIteratively() {
      val taskDescription = problemStatement.statement.dump()

      aiCodeEditor
          .attemptToCompleteTask(
              relevantFilePaths = problemScope.relevantFilePaths,
              taskDescription = taskDescription,
          )
          .editWithinWorktree()

      while (true) {
        if (iterationCount >= maxIterationCount) {
          throw IllegalStateException(
              "Reached maximum iteration count of $maxIterationCount while trying to solve the problem."
          )
        }

        ++iterationCount

        when (val formattingDiagnosis = codeProject.rootModule.formattingTool.diagnose()) {
          CodeModuleDiagnosis.Correct -> {
            // Great! The code was acceptably formatted. Now it's formatted conventionally.
          }

          is CodeModuleDiagnosis.Incorrect -> {
            aiCodeEditor
                .attemptToFixIssues(
                    originalRelevantFilePaths = problemScope.relevantFilePaths,
                    originalTaskDescription = taskDescription,
                    moduleDiagnosis = formattingDiagnosis,
                )
                .editWithinWorktree()

            continue
          }
        }

        when (val verificationDiagnosis = codeProject.rootModule.verificationTool.diagnose()) {
          CodeModuleDiagnosis.Correct -> {
            // Great! The code verifies.
          }

          is CodeModuleDiagnosis.Incorrect -> {
            aiCodeEditor
                .attemptToFixIssues(
                    originalRelevantFilePaths = problemScope.relevantFilePaths,
                    originalTaskDescription = taskDescription,
                    moduleDiagnosis = verificationDiagnosis,
                )
                .editWithinWorktree()

            continue
          }
        }

        // We're all good!
        break
      }
    }
  }

  override suspend fun solveProblem(
      codeProject: CodeProject,
      codeRootDirectory: MutableCompatFsDirectory,
      problemStatement: AiCodeEngineer.ProblemStatement,
      problemScope: ProblemScope,
  ) {
    val problemSolvingContext =
        ProblemSolvingContext(
            aiCodeEditor = aiCodeEditor,
            codeRootDirectory = codeRootDirectory,
            codeProject = codeProject,
            problemStatement = problemStatement,
            problemScope = problemScope,
        )

    problemSolvingContext.solveProblemIteratively()
  }
}
