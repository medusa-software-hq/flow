package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.FileEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer.ProblemScope
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.CodeProject.FormattingResult

class ProperAiCodeEngineer(
    private val aiCodeEditor: AiCodeEditor,
) : AiCodeEngineer {
  private class ProblemSolvingContext(
      private val aiCodeEditor: AiCodeEditor,
      private val codeProject: CodeProject,
      private val workingDirectory: MutableCompatFsDirectory,
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
          workingDirectory = workingDirectory,
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

        when (val formattingResult = codeProject.format()) {
          FormattingResult.Formatted -> {
            // Great! The code was acceptably formatted. Now it's formatted conventionally.
          }

          is FormattingResult.FoundSyntaxErrors -> {
            // Oops. Let's try to fix the formatting.

            // TODO: Separate loop to ensure that we first fix the diagnosed issues?
            // TODO: Some kind of log?

            TODO("Issue-fixing loop is not wired yet")

            // We don't know if the formatting issues are actually fixed. Go back to the start.
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
      problemStatement: AiCodeEngineer.ProblemStatement,
      problemScope: ProblemScope,
  ) {

    val problemSolvingContext =
        ProblemSolvingContext(
            aiCodeEditor = aiCodeEditor,
            codeProject = codeProject,
            problemStatement = problemStatement,
            problemScope = problemScope,
            workingDirectory = codeProject.workingDirectory,
        )

    problemSolvingContext.solveProblemIteratively()
  }
}
