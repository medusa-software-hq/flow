package software.medusa.flow.core_service.worker.code_project.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.relativizeAgainst
import software.medusa.commons.paths.toAbsoluteNioPath
import software.medusa.commons.process.ExecutableHandle
import software.medusa.commons.process.ProcessSpawner

class NpxTool(
    private val projectPath: LiteralAbsoluteUnixPath,
    private val args: List<String>,
    private val npxOutputParser: NpxOutputParser? = null,
    private val processSpawner: ProcessSpawner = ProcessSpawner.create(Runtime.getRuntime()),
    private val executableHandle: ExecutableHandle = ExecutableHandle.locate(commandName = "npx"),
) : CodeTool {
  override suspend fun diagnose(): CodeTool.CodeModuleDiagnosis =
      withContext(Dispatchers.IO) {
        val result =
            processSpawner.runCaptured(
                executableHandle = executableHandle,
                workingDirectoryPath = projectPath.toAbsoluteNioPath(),
                args = args,
                env = System.getenv(),
            )

        when (result.exitCode) {
          0 -> CodeTool.CodeModuleDiagnosis.Correct

          else -> buildIncorrectDiagnosis(output = result.output)
        }
      }

  private suspend fun buildIncorrectDiagnosis(
      output: String
  ): CodeTool.CodeModuleDiagnosis.Incorrect {
    val parsedDiagnosisByFilePath =
        when (val parser = npxOutputParser) {
          null -> emptyMap()

          else ->
              when (val parsedOutput = parser.parse(output)) {
                is NpxOutputParser.ParsedNpxOutput.Issues ->
                    parsedOutput.issues
                        .mapNotNull { issue ->
                          runCatching { issue.filePath.relativizeAgainst(basePath = projectPath) }
                              .getOrNull()
                              ?.let { relativePath ->
                                relativePath to CodeTool.CodeFileDiagnosis.Issue(issue.info)
                              }
                        }
                        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
                        .mapValues { (_, issues) ->
                          CodeTool.CodeFileDiagnosis(issues = issues.distinct())
                        }

                NpxOutputParser.ParsedNpxOutput.Error -> emptyMap()
              }
        }

    return CodeTool.CodeModuleDiagnosis.Incorrect(
        diagnosisByFilePath =
            parsedDiagnosisByFilePath.ifEmpty {
              mapOf(
                  RelativeUnixPath.of(projectPath.innerPath.names.last()) to
                      CodeTool.CodeFileDiagnosis(
                          issues =
                              listOf(
                                  CodeTool.CodeFileDiagnosis.Issue(
                                      description =
                                          output
                                              .lineSequence()
                                              .map(String::trim)
                                              .filter(String::isNotBlank)
                                              .firstOrNull()
                                              ?: "npx ${args.joinToString(separator = " ")} failed",
                                  ),
                              ),
                      ),
              )
            },
    )
  }
}
