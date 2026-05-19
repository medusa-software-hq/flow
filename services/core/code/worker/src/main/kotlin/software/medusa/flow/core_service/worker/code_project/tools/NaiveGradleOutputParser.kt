package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.toLiteral

/** Naive implementation of [GradleOutputParser], suitable for testing only. */
data object NaiveGradleOutputParser : GradleOutputParser {
  override suspend fun parse(
      gradleOutput: String,
  ): GradleOutputParser.ParsedGradleOutput {
    val issueRegex = Regex("""(?:e: )?file://(/[^:\s]+):([0-9]+):([0-9]+)\s+(.+)""")

    val issues =
        gradleOutput
            .lineSequence()
            .mapNotNull { line ->
              val match = issueRegex.matchEntire(line.trim()) ?: return@mapNotNull null

              val filePath =
                  AbsoluteUnixPath.parse(match.groupValues[1]).toLiteral() ?: return@mapNotNull null

              GradleOutputParser.GradleIssue(
                  filePath = filePath,
                  info =
                      "[${match.groupValues[2]}:${match.groupValues[3]}] ${match.groupValues[4]}",
              )
            }
            .toList()

    return when {
      issues.isNotEmpty() -> GradleOutputParser.ParsedGradleOutput.Issues(issues = issues)
      else -> GradleOutputParser.ParsedGradleOutput.Error
    }
  }
}
