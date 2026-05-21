package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.commons.paths.LiteralAbsoluteUnixPath

/** CconElementParser for npx/tool output that (likely) mentions issues within files. */
interface NpxOutputParser {
  data class NpxIssue(
      /** An absolute path to a file that has an issue, as mentioned in the tool output. */
      val filePath: LiteralAbsoluteUnixPath,
      /** Information about the issue, including line/column text if present. */
      val info: String,
  )

  sealed interface ParsedNpxOutput {
    data class Issues(
        val issues: List<NpxIssue>,
    ) : ParsedNpxOutput

    data object Error : ParsedNpxOutput
  }

  suspend fun parse(npxOutput: String): ParsedNpxOutput
}
