package software.medusa.flow.core_service.worker.code_project

import software.medusa.commons.paths.LiteralRelativeUnixPath

interface CodeTool {
  sealed class CodeModuleDiagnosis {
    /** Code module is correct (as far as the code tool can tell). */
    data object Correct : CodeModuleDiagnosis()

    /** Code module is not fully correct, issues were found. */
    data class Incorrect(
        val diagnosisByFilePath: Map<LiteralRelativeUnixPath, CodeFileDiagnosis>,
    ) : CodeModuleDiagnosis() {
      init {
        require(diagnosisByFilePath.isNotEmpty()) {
          "diagnosisByFilePath must not be empty for Incorrect diagnosis"
        }
      }

      val filePathsWithIssues: Set<LiteralRelativeUnixPath>
        get() = diagnosisByFilePath.keys
    }
  }

  data class CodeFileDiagnosis(
      val issues: List<Issue>,
  ) {
    data class Issue(
        val description: String,
    )

    init {
      require(issues.isNotEmpty()) { "issues must not be empty for CodeFileDiagnosis" }
    }
  }

  fun diagnose(): CodeModuleDiagnosis
}
