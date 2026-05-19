package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEngineer
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEditor
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeEngineer
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodeMasker
import software.medusa.flow.core_service.worker.ai_code_engineer.ProperAiCodePatcher
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code_project.YamlCodeProjectLoader
import software.medusa.flow.core_service.worker.code_project.tools.AiGradleOutputParser
import software.medusa.flow.core_service.worker.code_project.tools.AiNpxOutputParser
import software.medusa.openai_client.OpenAiClient

private const val openAiApiKeyEnvVarName = "OPENAI_API_KEY"
private const val openRouterApiKeyEnvVarName = "OPENROUTER_API_KEY"

fun main(args: Array<String>) {
  FlowCli().subcommands(AiEngineerCommand()).main(argv = args)
}

private class FlowCli : CliktCommand(name = "flow") {
  override fun run() = Unit
}

private class AiEngineerCommand : CliktCommand(name = "ai-engineer") {
  override fun run() = Unit

  init {
    subcommands(SolveProblemCommand())
  }
}

private class SolveProblemCommand : CliktCommand(name = "solve-problem") {
  private val worktreePathText by option("--path").required()
  private val taskDescriptionSource by argument()

  override fun run() {
    runBlocking {
      val worktreePath = parseExistingDirectory(worktreePathText)
      val worktreeUnixPath = worktreePath.toLiteralAbsoluteUnixPath()
      val taskDescription = readTaskDescription(taskDescriptionSource)

      val openAiClient = buildRequiredClient(
          apiKeyEnvVarName = openAiApiKeyEnvVarName,
          baseUrl = OpenAiClient.openAiBaseUrl,
      )
      val openRouterClient = buildRequiredClient(
          apiKeyEnvVarName = openRouterApiKeyEnvVarName,
          baseUrl = OpenAiClient.openRouterBaseUrl,
      )

      openAiClient.use { patchingClient ->
        openRouterClient.use { parsingClient ->
          val codeRootDirectory = NioCompatFsDirectory(directoryPath = worktreePath)
          val codeProject =
              YamlCodeProjectLoader(
                      gradleOutputParser = AiGradleOutputParser(openAiClient = parsingClient),
                      npxOutputParser = AiNpxOutputParser(openAiClient = parsingClient),
                  )
                  .loadProject(projectPath = worktreeUnixPath)

          val aiCodeEngineer =
              ProperAiCodeEngineer(
                  aiCodeEditor =
                      ProperAiCodeEditor(
                          aiCodePatcher = ProperAiCodePatcher(openAiClient = patchingClient),
                          aiCodeMasker = ProperAiCodeMasker(openAiClient = patchingClient),
                      ),
              )

          aiCodeEngineer.solveProblem(
              codeProject = codeProject,
              codeRootDirectory = codeRootDirectory,
              problemStatement =
                  AiCodeEngineer.ProblemStatement(
                      statement = CodeBlock.of(taskDescription),
                  ),
              problemScope =
                  AiCodeEngineer.ProblemScope(
                      relevantFilePaths = codeRootDirectory.collectAllFilePaths(),
                  ),
          )
        }
      }
    }
  }
}

private fun parseExistingDirectory(
    pathText: String,
): Path {
  val path = Path.of(pathText).absolute()

  if (!path.exists()) {
    throw PrintMessage("Path does not exist: $path", statusCode = 1)
  }

  if (!path.isDirectory()) {
    throw PrintMessage("Path is not a directory: $path", statusCode = 1)
  }

  return path
}

private fun Path.toLiteralAbsoluteUnixPath(): LiteralAbsoluteUnixPath =
    AbsoluteUnixPath.parse(toString()).toLiteral()
        ?: throw PrintMessage("Path must be a literal absolute Unix path: $this", statusCode = 1)

internal fun readTaskDescription(
    source: String,
): String =
    when (source) {
      "-" -> generateSequence { readlnOrNull() }.joinToString(separator = "\n")
      else -> Path.of(source).readText()
    }

private fun buildRequiredClient(
    apiKeyEnvVarName: String,
    baseUrl: java.net.URI,
): OpenAiClient {
  val apiKey =
      System.getenv(apiKeyEnvVarName)
          ?: throw PrintMessage("Environment variable $apiKeyEnvVarName is not set", statusCode = 1)

  return OpenAiClient.build(
      config =
          OpenAiClient.Config(
              baseUrl = baseUrl,
              apiKey = apiKey,
          ),
  )
}

private suspend fun MutableCompatFsDirectory.collectAllFilePaths(): Set<LiteralRelativeUnixPath> =
    collectAllFilePathsRecursively(
        directory = this,
        prefix = RelativeUnixPath.Empty.toLiteral()!!,
    )

private suspend fun collectAllFilePathsRecursively(
    directory: ReadonlyCompatFsDirectory,
    prefix: RelativeUnixPath<software.medusa.commons.paths.UnixPath.Name.Literal>,
): Set<LiteralRelativeUnixPath> =
    buildSet {
      directory.listEntries().forEach { entry ->
        val childEntity = entry.entity
        val childPath = LiteralRelativeUnixPath.of(prefix.names + entry.name)

        when (childEntity) {
          is ReadonlyCompatFsFile -> add(childPath)
          is ReadonlyCompatFsDirectory -> addAll(collectAllFilePathsRecursively(childEntity, childPath))
        }
      }
    }
