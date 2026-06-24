package software.medusa.flow.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import software.medusa.commons.git.worktree.GitWorktree
import software.medusa.commons.git.worktree.GitWorktreeFilter
import software.medusa.commons.markdown.MdDocument
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.commons.unix.filesystem.copyRecursivelyTo
import software.medusa.commons.unix.filesystem.impl.nio.UfsNioDirectory
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsProperSolutionCoder
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.integration.gradle.GrdProperProjectConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsNpmConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnectorHub
import software.medusa.flow.integration.nodejs.package_manager.NjsYarnConnector
import software.medusa.flow.integration.nodejs.process.NjsProcessPackageConnector
import software.medusa.flow.physical_workspace.PhwConnectorHub
import software.medusa.flow.physical_workspace.temp.PhwTempWorkspaceAllocator
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader
import software.medusa.flow.universal_project.yaml.UnpYamlProjectManifestLoader

private val terminal = Terminal()

class MainCommand(
    private val taskCompleter: HrsTaskCompleter,
) : CliktCommand() {
  companion object {
    private val targetWorkspacePrefix = UfsName.Literal("target-workspace-")
  }

  private val workDirPath: Path by
      argument(
              help = "The path to a working directory",
          )
          .path(
              mustExist = true,
              canBeFile = false,
              mustBeReadable = true,
          )

  private val taskDescriptionPath: Path by
      argument(
              help = "The path to the task description",
          )
          .path(
              mustExist = true,
              canBeDir = false,
              mustBeReadable = true,
          )

  override fun run() {
    runBlocking {
      val repoDirectory = UfsNioDirectory(directoryPath = workDirPath)

      val gitWorktree =
          GitWorktree.load(
              repoDirectory = repoDirectory,
              globalFilter = GitWorktreeFilter.GitCheckedOutWorktreeFilter,
          )

      val taskDescriptionText = taskDescriptionPath.readText()
      val taskDescriptionDocument = MdDocument.parse(markdownSource = taskDescriptionText)

      val taskDescription =
          HrsTaskDescription(
              body = taskDescriptionDocument.rootChapter.element,
          )

      val taskCompletionResult =
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
          )

      when (taskCompletionResult) {
        is HrsTaskCompleter.TaskCompletionResult.Success -> {
          taskCompletionResult.temporaryWorkspace.use { workspace ->
            val targetDirectory = UfsNioDirectory.createTemporary(prefix = targetWorkspacePrefix)

            terminal.println(
                "Target directory: ${TextColors.brightGreen(targetDirectory.directoryPath.toString())}",
            )

            workspace.rootDirectory.copyRecursivelyTo(targetDirectory = targetDirectory)
          }

          terminal.println(TextColors.green("Task completed"))
        }

        is HrsTaskCompleter.TaskCompletionResult.Failure.JointOperation -> {
          val phaseName =
              when (taskCompletionResult.phase) {
                HrsTaskCompleter.JointOperationPhase.ProjectBootstrapping -> "Project bootstrap"
                HrsTaskCompleter.JointOperationPhase.InitialProjectAnalysis -> "Initial analysis"
                HrsTaskCompleter.JointOperationPhase.InitialProjectTesting -> "Initial tests"
                HrsTaskCompleter.JointOperationPhase.FinalProjectAnalysis -> "Final analysis"
                HrsTaskCompleter.JointOperationPhase.FinalProjectTesting -> "Final tests"
              }

          terminal.println(TextColors.red("✗ $phaseName failed:"))
          terminal.println()

          taskCompletionResult.operationFailure.failureByModulePath.forEach {
              (modulePath, moduleFailure) ->
            terminal.println("Module ${TextColors.yellow(modulePath.toUnixAbsolutePathString())}:")
            terminal.println()
            terminal.println(TextColors.gray(0.3)(">>>>"))
            terminal.println(TextColors.gray(0.5)(moduleFailure.diagnosticOutput))
            terminal.println(TextColors.gray(0.3)("<<<<"))
          }
        }
      }
    }
  }
}

suspend fun main(
    args: Array<String>,
) {
  coroutineScope {
    val openRouterApiKey =
        OaiApiKey(
            System.getenv("OPENROUTER_API_KEY")
                ?: error("OPENROUTER_API_KEY environment variable is not set"),
        )

    val npmExecutableHandle = SysExecutableHandle.locate(commandName = "npm")

    val yarnExecutableHandle = SysExecutableHandle.locate(commandName = "yarn")

    val processSpawner = SysProcessSpawner()

    val connectorHub =
        PhwConnectorHub(
            gradleProjectConnector = GrdProperProjectConnector(),
            nodeJsPackageConnector =
                NjsProcessPackageConnector(
                    packageManagerConnectorHub =
                        NjsPackageManagerConnectorHub(
                            npmConnector =
                                NjsNpmConnector(
                                    npmExecutableHandle = npmExecutableHandle,
                                ),
                            yarnConnector =
                                NjsYarnConnector(
                                    yarnExecutableHandle = yarnExecutableHandle,
                                ),
                        ),
                    processSpawner = processSpawner,
                ),
        )

    val physicalWorkspaceAllocator =
        PhwTempWorkspaceAllocator(
            coroutineScope = this,
            connectorHub = connectorHub,
        )

    val openAiClient =
        OaiProperClient.withTarget(
                targetBaseUrl = OaiConfiguredClient.openRouterBaseUrl,
                targetApiKey = openRouterApiKey,
            )
            .withModel(
                model = OaiModel.DeepSeekFlash,
            )

    val solutionCoder =
        HrsProperSolutionCoder(
            openaiClient = openAiClient,
        )

    val projectManifestLoader =
        UnpYamlProjectManifestLoader(
            gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
            nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
        )

    val taskCompleter =
        HrsProperTaskCompleter(
            physicalWorkspaceAllocator = physicalWorkspaceAllocator,
            solutionCoder = solutionCoder,
            projectManifestLoader = projectManifestLoader,
        )

    MainCommand(
            taskCompleter = taskCompleter,
        )
        .main(args)
  }
}
