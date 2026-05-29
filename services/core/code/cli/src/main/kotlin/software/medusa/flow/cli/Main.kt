package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import software.medusa.code_agent.exploration.ProperCodeFileExplorer
import software.medusa.code_agent.scouting.ProperCodeWorktreePreScout
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.extractDeepReadonly
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.git.worktree.GitWorktree
import software.medusa.openai_client.OpenAiClient

private const val openAiApiKeyEnvVarName = "OPENAI_API_KEY"
private const val openRouterApiKeyEnvVarName = "OPENROUTER_API_KEY"

fun readTaskDescription(source: String): String = Files.readString(Path.of(source))

fun createOpenAiLogRootPath(): Path = Files.createTempDirectory("openai-logs-")

fun main(args: Array<String>) {
  FlowCli().subcommands(CodeAgentCommand()).main(argv = args)
}

private class FlowCli : CliktCommand(name = "flow") {
  override fun run() = Unit
}

private class CodeAgentCommand : CliktCommand(name = "code-agent") {
  override fun run() = Unit

  init {
    subcommands(PreScoutCommand())
  }
}

private class PreScoutCommand : CliktCommand(name = "pre-scout") {
  private val repoPathText by
      option(
              "--repo-path",
              help = "Literal absolute real path to the Git repository root",
          )
          .required()

  override fun run() {
    runBlocking {
      val openRouterApiKey =
          System.getenv(openRouterApiKeyEnvVarName)
              ?: throw PrintMessage(
                  message = "Environment variable $openRouterApiKeyEnvVarName is not set",
                  statusCode = 1,
              )

      val openAiClient =
          OpenAiClient.build(
              config =
                  OpenAiClient.Config(
                      baseUrl = OpenAiClient.openRouterBaseUrl,
                      apiKey = openRouterApiKey,
                  ),
          )

      val structureExtractor =
          ProperCodeFileExplorer(
              openAiClient = openAiClient,
          )

      val preScout =
          ProperCodeWorktreePreScout(
              structureExtractor = structureExtractor,
          )

      val repoAbsolutePath =
          AbsoluteUnixPath.parse(repoPathText).toLiteral()
              ?: throw PrintMessage(
                  message = "Expected repo path to be an absolute literal path, got: $repoPathText",
                  statusCode = 1,
              )

      val repoDirectory =
          NioCompatFsDirectory.Root.extractDeepReadonly(
              relativePath = repoAbsolutePath.innerPath,
          ) as? ReadonlyCompatFsDirectory
              ?: throw PrintMessage(
                  message =
                      "Expected ${repoAbsolutePath.toUnixAbsolutePathString()} path to exist and be a directory",
                  statusCode = 1,
              )

      val gitWorktree =
          GitWorktree.load(
              repoDirectory = repoDirectory,
          )

      val preScoutedVirtualWorkspace =
          preScout.preScoutWorktree(
              gitWorktree = gitWorktree,
          )
    }
  }
}
