package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.commons.paths.LiteralAbsoluteUnixPath

/** Parser for Gradle output that (likely) mentions issues within files. */
interface GradleOutputParser {
  data class GradleIssue(
      /**
       * An absolute path to a file that has an issue, as mentioned in the Gradle output.
       *
       * This is expected to be a _real path_, i.e. with symlinks resolved.
       */
      val filePath: LiteralAbsoluteUnixPath,
      /**
       * Information about the issue, as mentioned in the Gradle output. This may include line and
       * column numbers, error messages, etc.
       */
      val info: String,
  )

  sealed interface ParsedGradleOutput {
    data class Issues(
        /** A non-empty list of issues */
        val issues: List<GradleIssue>,
    ) : ParsedGradleOutput

    data object Error : ParsedGradleOutput
  }

  suspend fun parse(gradleOutput: String): ParsedGradleOutput
}
