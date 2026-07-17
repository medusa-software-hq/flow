package software.medusa.flow.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.coroutineScope
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.ai_system.HrsAiPatchInterpreter
import software.medusa.flow.harness.ai_system.HrsAiScoutDecisionInterpreter
import software.medusa.flow.harness.ai_system.HrsProperExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsProperFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsRetryingAiClient
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

    val openRouterClient =
        OaiProperClient.withTarget(
            targetBaseUrl = OaiConfiguredClient.openRouterBaseUrl,
            targetApiKey = openRouterApiKey,
        )

    // Product wiring pins the expert role to a stronger model; the hermetic loop test overrides it
    // to the cheap model (all three roles on DeepSeekFlash) via a test-only env — the only
    // override.
    val expertModel =
        if (System.getenv("FLOW_TEST_CHEAP_MODELS") != null) OaiModel.DeepSeekFlash
        else OaiModel.GptMidi

    openRouterClient.withModel(model = OaiModel.DeepSeekFlash).use { frontlineOpenAiClient ->
      openRouterClient.withModel(model = expertModel).use { expertOpenAiClient ->
        openRouterClient.withModel(model = OaiModel.DeepSeekFlash).use { interpreterOpenAiClient ->
          runMainCommand(
              args = args,
              physicalWorkspaceAllocator = physicalWorkspaceAllocator,
              frontlineOpenAiClient = frontlineOpenAiClient,
              expertOpenAiClient = expertOpenAiClient,
              interpreterOpenAiClient = interpreterOpenAiClient,
          )
        }
      }
    }
  }
}

private fun runMainCommand(
    args: Array<String>,
    physicalWorkspaceAllocator: PhwTempWorkspaceAllocator,
    frontlineOpenAiClient: OaiConfiguredClient,
    expertOpenAiClient: OaiConfiguredClient,
    interpreterOpenAiClient: OaiConfiguredClient,
) {
  // Every LLM call retries a transient empty response (see HrsRetryingAiClient) — wrapping here, at
  // the composition root, covers frontline/expert/interpreter for complete-task, scout-fully, and
  // work alike.
  val frontlineClient = HrsRetryingAiClient(delegate = frontlineOpenAiClient)
  val expertClient = HrsRetryingAiClient(delegate = expertOpenAiClient)
  val interpreterClient = HrsRetryingAiClient(delegate = interpreterOpenAiClient)

  val frontlineAiSystem = HrsProperFrontlineAiSystem(openaiClient = frontlineClient)

  val expertAiSystem = HrsProperExpertAiSystem(openaiClient = expertClient)

  val scoutDecisionInterpreter = HrsAiScoutDecisionInterpreter(openaiClient = interpreterClient)

  val patchInterpreter = HrsAiPatchInterpreter(openaiClient = interpreterClient)

  val projectManifestLoader =
      UnpYamlProjectManifestLoader(
          gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
          nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
      )

  val taskCompleter =
      HrsProperTaskCompleter(
          physicalWorkspaceAllocator = physicalWorkspaceAllocator,
          projectManifestLoader = projectManifestLoader,
          frontlineAiSystem = frontlineAiSystem,
          scoutDecisionInterpreter = scoutDecisionInterpreter,
          patchInterpreter = patchInterpreter,
          expertAiSystem = expertAiSystem,
      )

  val terminal = Terminal()

  RootCommand()
      .subcommands(
          ScoutFullyCommand(
              terminal = terminal,
              frontlineAiSystem = frontlineAiSystem,
              scoutDecisionInterpreter = scoutDecisionInterpreter,
          ),
          CompleteTaskCommand(
              terminal = terminal,
              taskCompleter = taskCompleter,
          ),
          WorkCommand(
              terminal = terminal,
              taskCompleter = taskCompleter,
          ),
      )
      .main(args)
}
