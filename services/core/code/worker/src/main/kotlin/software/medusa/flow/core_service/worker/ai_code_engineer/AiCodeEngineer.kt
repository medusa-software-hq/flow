package software.medusa.flow.core_service.worker.ai_code_engineer

import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.flow.core_service.worker.code_project.CodeProject

interface AiCodeEngineer {
  data class ProblemStatement(
      val statement: CodeProject.CodeBlock,
  )

  data class ProblemScope(
      val relevantFilePaths: Set<LiteralRelativeUnixPath>,
  )

  suspend fun solveProblem(
      codeProject: CodeProject,
      problemStatement: ProblemStatement,
      problemScope: ProblemScope,
  )
}
