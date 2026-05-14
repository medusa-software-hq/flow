package software.medusa.flow.lab

import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer.ProblemStatement
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEngineer
import software.medusa.flow.core_service.worker.code_project.CodeProject

suspend fun main() {
  val aiCodeEditor =
      ProperAiCodeEditor(
          openAiClient = null,
      )

  val aiCodeEngineer =
      ProperAiCodeEngineer(
          aiCodeEditor = aiCodeEditor,
      )

  val codeProject: CodeProject = null

  val problemStatement: ProblemStatement =
      ProblemStatement(
          statement = null,
      )

  val problemScope =
      AiCodeEngineer.ProblemScope(
          relevantFilePaths = null,
      )

  aiCodeEngineer.solveProblem(
      codeProject = codeProject,
      problemStatement = problemStatement,
      problemScope = problemScope,
  )

  println("Hello!")
}
