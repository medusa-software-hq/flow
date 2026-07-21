package software.medusa.flow.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.coroutineScope
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.HrsScriptedTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.UnimplementedHrsTaskCompleter
import software.medusa.flow.harness.ai_system.HrsAiPatchInterpreter
import software.medusa.flow.harness.ai_system.HrsAiScoutDecisionInterpreter
import software.medusa.flow.harness.ai_system.HrsLoggingOaiReporter
import software.medusa.flow.harness.ai_system.HrsProperExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsProperFrontlineAiSystem
import software.medusa.flow.harness.ai_system.HrsRetryingAiClient
import software.medusa.flow.harness.claude.HrsClaudeEngineConfig
import software.medusa.flow.harness.claude.HrsClaudeTaskCompleter
import software.medusa.flow.harness.claude.HrsProcessClaudeProcess
import software.medusa.flow.integration.gradle.GrdProperProjectConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsNpmConnector
import software.medusa.flow.integration.nodejs.package_manager.NjsPackageManagerConnectorHub
import software.medusa.flow.integration.nodejs.package_manager.NjsYarnConnector
import software.medusa.flow.integration.nodejs.process.NjsProcessPackageConnector
import software.medusa.flow.physical_workspace.PhwConnectorHub
import software.medusa.flow.physical_workspace.PhwWorkspaceAllocator
import software.medusa.flow.physical_workspace.temp.PhwTempWorkspaceAllocator
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader
import software.medusa.flow.universal_project.yaml.UnpYamlProjectManifestLoader
import software.medusa.flow.worker.WrkClaudeAuthEnvironment
import software.medusa.flow.worker.WrkEngineResolver

suspend fun main(
    args: Array<String>,
) {
  coroutineScope {
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

    // Test-only, hard-gated: a deterministic engine for the sad-path suite. Selected before the
    // OpenRouter client is even built, so it needs no API key — these flows never call a model.
    val scriptedBehavior = scriptedEngineBehaviorOrNull()
    if (scriptedBehavior != null) {
      runScriptedWorkerCommand(
          args = args,
          taskCompleter =
              HrsScriptedTaskCompleter(
                  physicalWorkspaceAllocator = physicalWorkspaceAllocator,
                  behavior = scriptedBehavior,
              ),
      )
      return@coroutineScope
    }

    // `claude` is a hard dependency of a real worker (the image ships it) — located once here, at
    // startup, so a missing binary fails cleanly and loudly rather than deep inside the first
    // claude session. Deliberately after the scripted-engine early return above: that test path
    // runs on a runner without `claude` and wires an UnimplementedHrsTaskCompleter instead.
    val claudeExecutableHandle = SysExecutableHandle.locate(commandName = "claude")

    val openRouterApiKey =
        OaiApiKey(
            System.getenv("OPENROUTER_API_KEY")
                ?: error("OPENROUTER_API_KEY environment variable is not set"),
        )

    // One shared reporter (0.2.0 hands the detail behind a coarse OaiResult/OaiResponse here);
    // wired
    // into the client at the target so every completion's anomalies are logged, not swallowed.
    val reporter = HrsLoggingOaiReporter()

    val openRouterClient =
        OaiProperClient.targeting(
            targetBaseUrl = OaiFreeClient.openRouterBaseUrl,
            targetApiKey = openRouterApiKey,
            reporter = reporter,
        )

    // Product wiring pins the expert role to a stronger model; the hermetic loop test overrides it
    // to the cheap model (all three roles on DeepSeekFlash) via a test-only env — the only
    // override.
    val expertModel =
        if (System.getenv("FLOW_TEST_CHEAP_MODELS") != null) OaiModel.DeepSeekFlash
        else OaiModel.GptMidi

    // The response format is fixed at configuration time in 0.2.0 (was per call), so the two
    // structured interpreters get their own JSON-configured clients while frontline/expert stay
    // plain text.
    runMainCommand(
        args = args,
        physicalWorkspaceAllocator = physicalWorkspaceAllocator,
        claudeExecutable = claudeExecutableHandle,
        frontlineOpenAiClient = openRouterClient.configured(model = OaiModel.DeepSeekFlash),
        expertOpenAiClient = openRouterClient.configured(model = expertModel),
        scoutDecisionOpenAiClient =
            openRouterClient.configured(
                model = OaiModel.DeepSeekFlash,
                responseFormat = HrsAiScoutDecisionInterpreter.responseFormat,
            ),
        patchOpenAiClient =
            openRouterClient.configured(
                model = OaiModel.DeepSeekFlash,
                responseFormat = HrsAiPatchInterpreter.responseFormat,
            ),
    )
  }
}

/**
 * Resolves the scripted worker engine from the environment, or null for the normal AI engine.
 *
 * Hard-gated: selecting it requires `FLOW_ALLOW_SCRIPTED_ENGINE=1` in addition to
 * `FLOW_WORKER_ENGINE=scripted`, so a production worker — which sets neither — can never reach it.
 * `crash-after-publish` produces a real patch; the crash itself is applied in [WorkCommand] after
 * the push.
 */
private fun scriptedEngineBehaviorOrNull(): HrsScriptedTaskCompleter.Behavior? {
  if (System.getenv("FLOW_WORKER_ENGINE") != "scripted") return null

  require(System.getenv("FLOW_ALLOW_SCRIPTED_ENGINE") == "1") {
    "The scripted worker engine is a test-only engine; it is refused unless FLOW_ALLOW_SCRIPTED_ENGINE=1."
  }

  return when (val behaviorName = System.getenv("FLOW_SCRIPTED_ENGINE_BEHAVIOR")) {
    "patch",
    "crash-after-publish" -> HrsScriptedTaskCompleter.Behavior.Patch
    "empty-diff" -> HrsScriptedTaskCompleter.Behavior.EmptyDiff
    "attempts-exhausted" -> HrsScriptedTaskCompleter.Behavior.AttemptsExhausted
    "hang" -> HrsScriptedTaskCompleter.Behavior.Hang
    else -> error("Unknown FLOW_SCRIPTED_ENGINE_BEHAVIOR: $behaviorName")
  }
}

/** The scripted engine only ever drives `work`; scouting/planning need a real model. */
private fun runScriptedWorkerCommand(
    args: Array<String>,
    taskCompleter: HrsTaskCompleter,
) {
  val terminal = Terminal()

  RootCommand()
      .subcommands(
          WorkCommand(
              terminal = terminal,
              engineResolver = singleEngineResolver(taskCompleter),
          ),
      )
      .main(args)
}

/**
 * A resolver for the scripted sad-path engine, which only ever runs `UNSPECIFIED`/builtin sessions:
 * [taskCompleter] takes the builtin slot, and the claude slot is an [UnimplementedHrsTaskCompleter]
 * — this test path runs on a runner without the `claude` binary, so it must not try to build the
 * real claude engine (which would locate `claude` at startup).
 */
private fun singleEngineResolver(
    taskCompleter: HrsTaskCompleter,
): WrkEngineResolver =
    WrkEngineResolver(
        builtin = taskCompleter,
        claude = UnimplementedHrsTaskCompleter(engineName = "claude"),
    )

/**
 * Assembles the static engine resolver: workers are uniform, so **both** the builtin and claude
 * completers are always constructed. The claude engine drives the real `claude` binary
 * ([claudeExecutable], located at startup) with its auth-rung env built from `FLOW_CLAUDE_AUTH`.
 */
private fun buildEngineResolver(
    claudeExecutable: SysExecutableHandle,
    builtinTaskCompleter: HrsTaskCompleter,
    physicalWorkspaceAllocator: PhwWorkspaceAllocator,
    projectManifestLoader: UnpProjectManifestLoader,
): WrkEngineResolver =
    WrkEngineResolver(
        builtin = builtinTaskCompleter,
        claude =
            HrsClaudeTaskCompleter(
                physicalWorkspaceAllocator = physicalWorkspaceAllocator,
                claudeProcess = HrsProcessClaudeProcess(claudeExecutable = claudeExecutable),
                config =
                    HrsClaudeEngineConfig(
                        authEnvironment = WrkClaudeAuthEnvironment.build(),
                        model = System.getenv("FLOW_CLAUDE_MODEL")?.takeIf { it.isNotBlank() },
                    ),
                projectManifestLoader = projectManifestLoader,
            ),
    )

private fun runMainCommand(
    args: Array<String>,
    physicalWorkspaceAllocator: PhwTempWorkspaceAllocator,
    claudeExecutable: SysExecutableHandle,
    frontlineOpenAiClient: OaiConfiguredClient,
    expertOpenAiClient: OaiConfiguredClient,
    scoutDecisionOpenAiClient: OaiConfiguredClient,
    patchOpenAiClient: OaiConfiguredClient,
) {
  // Every LLM call retries a transient empty response (see HrsRetryingAiClient) — wrapping here, at
  // the composition root, covers frontline/expert/interpreter for complete-task, scout-fully, and
  // work alike.
  val frontlineClient = HrsRetryingAiClient(delegate = frontlineOpenAiClient)
  val expertClient = HrsRetryingAiClient(delegate = expertOpenAiClient)
  val scoutDecisionClient = HrsRetryingAiClient(delegate = scoutDecisionOpenAiClient)
  val patchClient = HrsRetryingAiClient(delegate = patchOpenAiClient)

  val frontlineAiSystem = HrsProperFrontlineAiSystem(openaiClient = frontlineClient)

  val expertAiSystem = HrsProperExpertAiSystem(openaiClient = expertClient)

  val scoutDecisionInterpreter = HrsAiScoutDecisionInterpreter(openaiClient = scoutDecisionClient)

  val patchInterpreter = HrsAiPatchInterpreter(openaiClient = patchClient)

  val projectManifestLoader =
      UnpYamlProjectManifestLoader(
          gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
          nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
      )

  // The builtin engine (classic AI-system graph). `complete-task` always drives it directly; `work`
  // reaches it (and the claude engine) through the engine resolver.
  val builtinTaskCompleter =
      HrsProperTaskCompleter(
          physicalWorkspaceAllocator = physicalWorkspaceAllocator,
          projectManifestLoader = projectManifestLoader,
          frontlineAiSystem = frontlineAiSystem,
          scoutDecisionInterpreter = scoutDecisionInterpreter,
          patchInterpreter = patchInterpreter,
          expertAiSystem = expertAiSystem,
      )

  val engineResolver =
      buildEngineResolver(
          claudeExecutable = claudeExecutable,
          builtinTaskCompleter = builtinTaskCompleter,
          physicalWorkspaceAllocator = physicalWorkspaceAllocator,
          projectManifestLoader = projectManifestLoader,
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
              taskCompleter = builtinTaskCompleter,
          ),
          WorkCommand(
              terminal = terminal,
              engineResolver = engineResolver,
          ),
      )
      .main(args)
}
