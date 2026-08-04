package software.medusa.flow.cli

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.mordant.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.system.SysExecutableHandle
import software.medusa.commons.system.SysProcessSpawner
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.HrsScriptedTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter
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
import software.medusa.flow.physical_workspace.temp.PhwTempWorkspaceAllocator
import software.medusa.flow.universal_project.UnpProjectManifestLoader
import software.medusa.flow.universal_project.gradle.UnpGradleModuleManifestLoader
import software.medusa.flow.universal_project.nodejs.UnpNodeJsModuleManifestLoader
import software.medusa.flow.universal_project.yaml.UnpYamlProjectManifestLoader
import software.medusa.flow.worker.WrkClaudeAuthEnvironment
import software.medusa.flow.worker.WrkEngineResolver

/**
 * The CLI is a **read-only client** for the deployed Flow API — `sessions`, `pipelines`, and the
 * `login`/`logout` that authenticate them — plus `work`, the worker loop that is the container
 * image's entrypoint. The old offline engine commands (`complete-task`, `scout-fully`, which ran
 * the engine against a local directory with no control plane) were dropped.
 *
 * The engine composition (builtin frontline/expert/interpreter AI systems + the claude completer)
 * is built lazily, only when the invocation is actually the worker path — so `flow sessions` needs
 * no `OPENROUTER_API_KEY`, locates no `npm`/`yarn`/`claude`, and constructs nothing model-related.
 */
suspend fun main(
    args: Array<String>,
) {
  coroutineScope {
    val terminal = Terminal()

    // Test-only, hard-gated: a deterministic engine for the sad-path suite. Selected before any
    // OpenRouter client is built, so it needs no API key — these flows never call a model, and it
    // only ever drives `work`.
    val scriptedBehavior = scriptedEngineBehaviorOrNull()
    if (scriptedBehavior != null) {
      RootCommand()
          .subcommands(
              WorkCommand(
                  terminal = terminal,
                  engineResolverProvider = { buildScriptedEngineResolver(this, scriptedBehavior) },
              ),
          )
          .main(args)
      return@coroutineScope
    }

    RootCommand()
        .subcommands(
            WorkCommand(
                terminal = terminal,
                engineResolverProvider = { buildWorkerEngineResolver(this) },
            ),
            SessionsCommand()
                .subcommands(
                    SessionsListCommand(),
                    SessionsShowCommand(),
                    SessionsAbortCommand(),
                ),
            PipelinesCommand().subcommands(PipelinesListCommand(), PipelinesClearCommand()),
            SettingsCommand()
                .subcommands(SettingsSetCommand().subcommands(SettingsSetAutoMergeCommand())),
            LoginCommand(),
            LogoutCommand(),
        )
        .main(args)
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

/** The physical-workspace allocator shared by both the real and scripted worker engines. */
private fun buildPhysicalWorkspaceAllocator(
    scope: CoroutineScope,
): PhwTempWorkspaceAllocator {
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
                          npmConnector = NjsNpmConnector(npmExecutableHandle = npmExecutableHandle),
                          yarnConnector =
                              NjsYarnConnector(yarnExecutableHandle = yarnExecutableHandle),
                      ),
                  processSpawner = processSpawner,
              ),
      )

  return PhwTempWorkspaceAllocator(coroutineScope = scope, connectorHub = connectorHub)
}

/**
 * A resolver for the scripted sad-path engine. Dual-engine fan-out creates a Claude **primary**
 * session for every issue (and the primary is what drives the pipeline), so the scripted worker is
 * now routed a Claude session too. Both engine slots are served from the one scripted completer —
 * no real `claude` binary is located, so this still runs on a claude-less runner.
 */
private fun buildScriptedEngineResolver(
    scope: CoroutineScope,
    behavior: HrsScriptedTaskCompleter.Behavior,
): WrkEngineResolver {
  val scripted =
      HrsScriptedTaskCompleter(
          physicalWorkspaceAllocator = buildPhysicalWorkspaceAllocator(scope),
          behavior = behavior,
      )
  return WrkEngineResolver(builtin = scripted, claude = scripted)
}

/**
 * Assembles the full worker engine composition: the builtin AI-system graph (frontline/expert plus
 * the scout-decision and patch interpreters, each on its own OpenRouter-configured client) and the
 * claude completer driving the real `claude` binary. Built only when `work` actually runs — this is
 * the only path that requires `OPENROUTER_API_KEY` and the `npm`/`yarn`/`claude` executables.
 */
private fun buildWorkerEngineResolver(
    scope: CoroutineScope,
): WrkEngineResolver {
  val physicalWorkspaceAllocator = buildPhysicalWorkspaceAllocator(scope)

  // Fan-out makes every pipeline's *primary* session Claude. The hermetic builtin loop test runs
  // that primary on the builtin engine (via this test-only env) so it needs no real `claude`
  // binary/auth; a real worker leaves it unset and drives the actual `claude` binary.
  val routeClaudeToBuiltin = System.getenv("FLOW_TEST_CLAUDE_AS_BUILTIN") != null

  // `claude` is a hard dependency of a real worker (the image ships it) — located once here so a
  // missing binary fails cleanly and loudly rather than deep inside the first claude session.
  val claudeExecutableHandle =
      if (routeClaudeToBuiltin) null else SysExecutableHandle.locate(commandName = "claude")

  val openRouterApiKey =
      OaiApiKey(
          System.getenv("OPENROUTER_API_KEY")
              ?: error("OPENROUTER_API_KEY environment variable is not set"),
      )

  // One shared reporter so every completion's anomalies are logged, not swallowed.
  val reporter = HrsLoggingOaiReporter()

  val openRouterClient =
      OaiProperClient.targeting(
          targetBaseUrl = OaiFreeClient.openRouterBaseUrl,
          targetApiKey = openRouterApiKey,
          reporter = reporter,
      )

  // Product wiring pins the expert role to a stronger model; the hermetic loop test overrides it to
  // the cheap model (all three roles on DeepSeekFlash) via a test-only env — the only override.
  val expertModel =
      if (System.getenv("FLOW_TEST_CHEAP_MODELS") != null) OaiModel.DeepSeekFlash
      else OaiModel.GptMidi

  // Every LLM call retries a transient empty response (see HrsRetryingAiClient); the response
  // format
  // is fixed at configuration time in commons 0.2.0, so the two structured interpreters get their
  // own JSON-configured clients while frontline/expert stay plain text.
  val frontlineClient =
      HrsRetryingAiClient(delegate = openRouterClient.configured(model = OaiModel.DeepSeekFlash))
  val expertClient =
      HrsRetryingAiClient(delegate = openRouterClient.configured(model = expertModel))
  val scoutDecisionClient =
      HrsRetryingAiClient(
          delegate =
              openRouterClient.configured(
                  model = OaiModel.DeepSeekFlash,
                  responseFormat = HrsAiScoutDecisionInterpreter.responseFormat,
              ),
      )
  val patchClient =
      HrsRetryingAiClient(
          delegate =
              openRouterClient.configured(
                  model = OaiModel.DeepSeekFlash,
                  responseFormat = HrsAiPatchInterpreter.responseFormat,
              ),
      )

  val frontlineAiSystem = HrsProperFrontlineAiSystem(openaiClient = frontlineClient)
  val expertAiSystem = HrsProperExpertAiSystem(openaiClient = expertClient)
  val scoutDecisionInterpreter = HrsAiScoutDecisionInterpreter(openaiClient = scoutDecisionClient)
  val patchInterpreter = HrsAiPatchInterpreter(openaiClient = patchClient)

  val projectManifestLoader: UnpProjectManifestLoader =
      UnpYamlProjectManifestLoader(
          gradleModuleManifestLoader = UnpGradleModuleManifestLoader,
          nodeJsModuleManifestLoader = UnpNodeJsModuleManifestLoader,
      )

  val builtinTaskCompleter: HrsTaskCompleter =
      HrsProperTaskCompleter(
          physicalWorkspaceAllocator = physicalWorkspaceAllocator,
          projectManifestLoader = projectManifestLoader,
          frontlineAiSystem = frontlineAiSystem,
          scoutDecisionInterpreter = scoutDecisionInterpreter,
          patchInterpreter = patchInterpreter,
          expertAiSystem = expertAiSystem,
      )

  // Workers are uniform, so both the builtin and claude completers are always constructed. The
  // claude engine drives the real `claude` binary with its auth-rung env built from
  // `FLOW_CLAUDE_AUTH` — except in the hermetic loop test, which routes the Claude primary to the
  // builtin completer (routeClaudeToBuiltin) so it stays cheap and needs no real claude.
  return WrkEngineResolver(
      builtin = builtinTaskCompleter,
      claude =
          if (routeClaudeToBuiltin) builtinTaskCompleter
          else
              HrsClaudeTaskCompleter(
                  physicalWorkspaceAllocator = physicalWorkspaceAllocator,
                  claudeProcess =
                      HrsProcessClaudeProcess(claudeExecutable = claudeExecutableHandle!!),
                  config =
                      HrsClaudeEngineConfig(
                          authEnvironment = WrkClaudeAuthEnvironment.build(),
                          model = System.getenv("FLOW_CLAUDE_MODEL")?.takeIf { it.isNotBlank() },
                          // Per-env cap tuning without a rebuild (set in the ms-workload profile);
                          // falls back to the baked default when unset/blank/unparseable.
                          maxBudgetUsd =
                              System.getenv("FLOW_CLAUDE_MAX_BUDGET_USD")
                                  ?.takeIf { it.isNotBlank() }
                                  ?.toDoubleOrNull() ?: HrsClaudeEngineConfig.defaultMaxBudgetUsd,
                      ),
                  projectManifestLoader = projectManifestLoader,
              ),
  )
}
