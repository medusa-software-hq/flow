package software.medusa.flow.core_service.worker.code_project.tools

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.relativizeAgainst
import software.medusa.commons.paths.toIoFile

class GradleTool(
    /**
     * A path to a Gradle project (possibly a nested one).
     *
     * This is expected to be a _real_ path, with symlinks resolved.
     */
    private val projectPath: LiteralAbsoluteUnixPath,
    /** A name of a Gradle task to run. For example, "build" or "test". */
    private val taskName: String,
    /** A parser for Gradle output that can extract file-level issues from it. */
    private val gradleOutputParser: GradleOutputParser,
) : CodeTool {
  override suspend fun diagnose(): CodeTool.CodeModuleDiagnosis =
      withContext(Dispatchers.IO) {
        val stdoutStream = ByteArrayOutputStream() // For now, we ignore the stdout
        val stderrStream = ByteArrayOutputStream()

        try {
          GradleConnector.newConnector()
              .forProjectDirectory(projectPath.toIoFile())
              .connect()
              .use { connection ->
                val buildLauncher =
                    connection
                        .newBuild()
                        .forTasks(taskName)
                        .setStandardOutput(stdoutStream)
                        .setStandardError(stderrStream)

                buildLauncher.run()
              }

          CodeTool.CodeModuleDiagnosis.Correct
        } catch (_: GradleConnectionException) {
          val errorOutput = stderrStream.toString(Charsets.UTF_8)

          val diagnosisByFilePath =
              when (val parsedOutput = gradleOutputParser.parse(gradleOutput = errorOutput)) {
                is GradleOutputParser.ParsedGradleOutput.Issues ->
                    parsedOutput.issues
                        .groupBy(
                            keySelector = { issue ->
                              issue.filePath.relativizeAgainst(basePath = projectPath)
                            },
                            valueTransform = { issue ->
                              CodeTool.CodeFileDiagnosis.Issue(issue.info)
                            },
                        )
                        .map { (filePath, issues) ->
                          filePath to CodeTool.CodeFileDiagnosis(issues = issues.distinct())
                        }
                        .toMap()

                GradleOutputParser.ParsedGradleOutput.Error -> emptyMap()
              }

          CodeTool.CodeModuleDiagnosis.Incorrect(
              diagnosisByFilePath = diagnosisByFilePath,
          )
        }
      }
}
