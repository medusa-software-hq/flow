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
import software.medusa.code_agent.scouting.ProperCodeWorktreePreScout
import software.medusa.code_agent.virtual_workspace.CodeVirtualWorkspace_markdownUtils.encodeToMarkdownDocument
import software.medusa.code_agent.virtual_workspace.document.ProperCodeDocumentBootstrapper
import software.medusa.commons.filesystem.compat.ReadonlyCompatFsDirectory
import software.medusa.commons.filesystem.compat.extractDeepReadonly
import software.medusa.commons.filesystem.compat.impl.nio.NioCompatFsDirectory
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.git.worktree.GitFsNodeKind
import software.medusa.git.worktree.GitWorktree
import software.medusa.git.worktree.GitWorktreeFilter
import software.medusa.git.worktree.chain
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
  private data object IdeaFilter : GitWorktreeFilter {
    private val ideaName = UnixPath.Name.Literal(".idea")

    override fun classify(
        path: LiteralRelativeUnixPath,
        nodeKind: GitFsNodeKind,
    ): GitWorktreeFilter.Classification? =
        when {
          path == LiteralRelativeUnixPath.of(ideaName) -> GitWorktreeFilter.Classification.Ignore
          else -> null
        }
  }

  private val gitGlobalFilter = IdeaFilter.chain(GitWorktreeFilter.GitCheckedOutWorktreeFilter)

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

      val documentBootstrapper =
          ProperCodeDocumentBootstrapper(
              openAiClient = openAiClient,
          )

      val preScout =
          ProperCodeWorktreePreScout(
              documentBootstrapper = documentBootstrapper,
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
              globalFilter = gitGlobalFilter,
          )

      val preScoutedVirtualWorkspace =
          preScout.preScoutWorktree(
              gitWorktree = gitWorktree,
          )

      val preScoutedVirtualWorkspaceMarkdownDocument =
          preScoutedVirtualWorkspace.encodeToMarkdownDocument()

      print(preScoutedVirtualWorkspaceMarkdownDocument.toMarkdownString())
    }
  }
}
