package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionInstructions
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionScope
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer.ProblemScope
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.flow.core_service.worker.code_project.CodeProject
import software.medusa.flow.core_service.worker.code_project.CodeProject.AnalysisResult
import software.medusa.flow.core_service.worker.code_project.CodeProject.FormattingResult
import software.medusa.flow.core_service.worker.code_project.CodeProject.TestingResult
import software.medusa.flow.core_service.worker.code_project.readFile

class ProperAiCodeEngineer(
    private val aiCodeEditor: AiCodeEditor,
) : AiCodeEngineer {
  private class ProblemSolvingContext(
      private val aiCodeEditor: AiCodeEditor,
      private val codeProject: CodeProject,
      private val problemStatement: AiCodeEngineer.ProblemStatement,
      private val problemScope: ProblemScope,
  ) {
    companion object {
      private const val maxIterationCount = 8
    }

    /** Count of started iterations. */
    private var iterationCount = 0

    suspend fun solveProblemIteratively() {
      attemptToSolveTheProblem()

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

            attemptToFixFormattingIssues(
                formattingResult = formattingResult,
            )

            // We don't know if the formatting issues are actually fixed. Go back to the start.
            continue
          }
        }

        when (val analysisResult = codeProject.analyze()) {
          AnalysisResult.Accepted -> {
            // Great! No semantic issues found.
          }

          is AnalysisResult.Rejected -> {
            // Oops. Let's try to fix the found semantic issues.

            attemptToFixSemanticIssues(
                analysisResult = analysisResult,
            )

            // We might've introduced new formatting issues and the analysis issues might not be
            // properly fixed. Go back to the start.
            continue
          }
        }

        when (val testingResult = codeProject.test()) {
          TestingResult.AllPassed -> {
            // Great! All tests are green.
          }

          is TestingResult.SomeFailed -> {
            // Oops. Let's try to fix the failing tests.

            attemptToFixFailingTests(
                testingResult = testingResult,
            )

            // We might've introduced new formatting and semantic issues. Some tests might still
            // fail.
            // Go back to the start.
            continue
          }
        }

        // We're all good!
        break
      }
    }

    private suspend fun editCode(
        editionInstructions: EditionInstructions,
    ) {
      val editionScope = EditionScope(
          problemScope.relevantFilePaths.associateWith { filePath ->
            val codeFileContent = codeProject.readFile(filePath)
            val maskedCodeFileContent = codeFileContent.toMaskedCodeFileContent()

            maskedCodeFileContent
          },
      )

      aiCodeEditor.editCode(
          codeProject = codeProject,
          editionInstructions = editionInstructions,
          editionScope = editionScope,
      )
    }

    private suspend fun attemptToSolveTheProblem() {
      editCode(
          editionInstructions =
              EditionInstructions(
                  // Pass the problem statement as-is
                  instructions = problemStatement.statement,
              ),
      )
    }

    private suspend fun attemptToFixFormattingIssues(
        formattingResult: FormattingResult.FoundSyntaxErrors,
    ) {
      val formatterOutputBlock = CodeBlock.parse(formattingResult.formatterOutput)

      editCode(
          editionInstructions =
              EditionInstructions(
                  instructions =
                      CodeBlock.concat(
                          CodeBlock.of(
                              "Formatting failed. Fix it.",
                              "",
                              "Raw formatter output:",
                              "",
                          ),
                          formatterOutputBlock,
                      ),
              ),
      )
    }

    private suspend fun attemptToFixSemanticIssues(
        analysisResult: AnalysisResult.Rejected,
    ) {
      val analyzerOutputBlock = CodeBlock.parse(analysisResult.analyzerOutput)

      editCode(
          editionInstructions =
              EditionInstructions(
                  instructions =
                      CodeBlock.concat(
                          CodeBlock.of(
                              "Analysis failed. Fix it.",
                              "",
                              "Raw analyzer output:",
                              "",
                          ),
                          analyzerOutputBlock,
                      ),
              ),
      )
    }

    private suspend fun attemptToFixFailingTests(
        testingResult: TestingResult.SomeFailed,
    ) {
      val testingOutputBlock = CodeBlock.parse(testingResult.testingOutput)

      editCode(
          editionInstructions =
              EditionInstructions(
                  instructions =
                      CodeBlock.concat(
                          CodeBlock.of(
                              "Some tests are failing. Fix it.",
                              "",
                              "Raw testing output:",
                              "",
                          ),
                          testingOutputBlock,
                      ),
              ),
      )
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
        )

    problemSolvingContext.solveProblemIteratively()
  }
}

private fun CodeProject.BulkCodeFileContent.toEditionScope(): EditionScope =
    EditionScope(
        maskedCodeFileContentByPath =
            codeFileContentByPath.mapValues { (_, fileContent) ->
              fileContent.toMaskedCodeFileContent()
            },
    )

private fun CodeFileContent.toMaskedCodeFileContent(): MaskedCodeFileContent =
    MaskedCodeFileContent(
        blocks =
            listOf(
                MaskedCodeFileContent.ContentBlock(
                    startIndex = LineIndex.First,
                    content = code,
                ),
            ),
    )
