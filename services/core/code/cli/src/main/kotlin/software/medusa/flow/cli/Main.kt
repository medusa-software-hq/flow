package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import software.medusa.commons.filesystem.compat.MutableCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsFile
import software.medusa.commons.filesystem.compat.extractDeepMutable
import software.medusa.commons.filesystem.compat.extractDeepReadonly
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.resolve
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
import software.medusa.git.worktree.GitWorktreeFilter
import software.medusa.git.worktree.filtered
import software.medusa.openai_client.FilesystemOpenAiLogger
import software.medusa.openai_client.LoggingOpenAiClient
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
  private val repoPathText by
      option(
              "--repo-path",
              help = "Literal absolute real path to the Git repository root",
          )
          .required()

  private val modulePathText by
      option(
              "--module-path",
              help = "Literal relative real path to the module (relative to the repository root)",
          )
          .required()

  private val taskDescriptionSource by
      argument(
          name = "task-description-path",
          help = "Path to task description (- means stdin)",
      )

  override fun run() {
    runBlocking {
      val taskDescription = readTaskDescription(taskDescriptionSource)
      val problemStatementBlock = CodeBlock.parse(taskDescription)

      val repoPath =
          AbsoluteUnixPath.parse(repoPathText).toLiteral()
              ?: throw PrintMessage(
                  "Repo path must be a literal absolute Unix path: $repoPathText",
                  statusCode = 1,
              )

      val modulePath =
          RelativeUnixPath.parse(modulePathText).toLiteral()
              ?: throw PrintMessage(
                  "Module path must be a literal relative Unix path: $modulePathText",
                  statusCode = 1,
              )

      val repoDirectory =
          NioCompatFsDirectory.Root.extractDeepReadonly(
              relativePath = repoPath.innerPath,
          ) as? MutableCompatFsDirectory
              ?: throw PrintMessage(
                  "Repo path does not exist or is not a directory: $repoPath",
                  statusCode = 1,
              )

      val filteredRepoDirectory = repoDirectory.filtered(baseFilter = GitWorktreeFilter.Passive)

      val moduleDirectory =
          repoDirectory.extractDeepMutable(relativePath = modulePath) as? MutableCompatFsDirectory
              ?: throw PrintMessage(
                  "Module path does not exist within the repository or is not a directory: $modulePath",
                  statusCode = 1,
              )

      val filteredModuleDirectory =
          filteredRepoDirectory.extractDeepReadonly(relativePath = modulePath)
              as? ReadonlyCompatFsDirectory
              ?: throw PrintMessage(
                  "Module path does not (visibly) exist within the repository: $modulePath Is it git-ignored?",
                  statusCode = 1,
              )

      val openAiLogRootPath = createOpenAiLogRootPath()

      println(openAiLogRootPath)

      val openAiClient =
          buildRequiredClient(
              apiKeyEnvVarName = openAiApiKeyEnvVarName,
              baseUrl = OpenAiClient.openAiBaseUrl,
              logDirectoryPath = openAiLogRootPath.resolve("logs").resolve("openai"),
          )

      val openRouterClient =
          buildRequiredClient(
              apiKeyEnvVarName = openRouterApiKeyEnvVarName,
              baseUrl = OpenAiClient.openRouterBaseUrl,
              logDirectoryPath = openAiLogRootPath.resolve("logs").resolve("openrouter"),
          )

      openAiClient.use { patchingClient ->
        openRouterClient.use { parsingClient ->
          val aiCodeEngineer =
              ProperAiCodeEngineer(
                  aiCodeEditor =
                      ProperAiCodeEditor(
                          aiCodePatcher = ProperAiCodePatcher(openAiClient = patchingClient),
                          aiCodeMasker = ProperAiCodeMasker(openAiClient = patchingClient),
                      ),
              )

          val codeProject =
              YamlCodeProjectLoader(
                      gradleOutputParser = AiGradleOutputParser(openAiClient = parsingClient),
                      npxOutputParser = AiNpxOutputParser(openAiClient = parsingClient),
                  )
                  .loadProject(
                      projectDirectory = moduleDirectory as ReadonlyCompatFsDirectory,
                      projectPath = repoPath.resolve(modulePath),
                  )

          val relevantFilePaths = filteredModuleDirectory.collectAllFilePaths()

          println("Relevant file paths:")
          relevantFilePaths.forEach { println(it.toUnixRelativePathString()) }

          aiCodeEngineer.solveProblem(
              codeProject = codeProject,
              codeRootDirectory = moduleDirectory,
              problemStatement =
                  AiCodeEngineer.ProblemStatement(
                      statement = problemStatementBlock,
                  ),
              problemScope =
                  AiCodeEngineer.ProblemScope(
                      relevantFilePaths = relevantFilePaths,
                  ),
          )
        }
      }
    }
  }
}

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
    logDirectoryPath: Path,
): OpenAiClient {
  val apiKey =
      System.getenv(apiKeyEnvVarName)
          ?: throw PrintMessage("Environment variable $apiKeyEnvVarName is not set", statusCode = 1)

  val properClient =
      OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = baseUrl,
                  apiKey = apiKey,
              ),
      )

  val logger =
      FilesystemOpenAiLogger(
          logDirectoryPath = logDirectoryPath,
          clock = Clock.systemUTC(),
      )

  val loggingOpenAiClient = LoggingOpenAiClient(baseClient = properClient, logger = logger)

  return loggingOpenAiClient
}

internal fun createOpenAiLogRootPath(): Path = Files.createTempDirectory("flow-cli-")

private suspend fun ReadonlyCompatFsDirectory.collectAllFilePaths(): Set<LiteralRelativeUnixPath> =
    collectAllFilePathsRecursively(
        directory = this,
        prefix = RelativeUnixPath.Empty.toLiteral()!!,
    )

private suspend fun collectAllFilePathsRecursively(
    directory: ReadonlyCompatFsDirectory,
    prefix: RelativeUnixPath<software.medusa.commons.paths.UnixPath.Name.Literal>,
): Set<LiteralRelativeUnixPath> = buildSet {
  directory.listEntries().forEach { entry ->
    val childEntity = entry.entity
    val childPath = LiteralRelativeUnixPath.of(prefix.names + entry.name)

    when (childEntity) {
      is ReadonlyCompatFsFile -> add(childPath)
      is ReadonlyCompatFsDirectory -> addAll(collectAllFilePathsRecursively(childEntity, childPath))
    }
  }
}
