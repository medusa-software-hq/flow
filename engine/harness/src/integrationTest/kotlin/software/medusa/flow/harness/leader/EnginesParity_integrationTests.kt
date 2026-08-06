package software.medusa.flow.harness.leader

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.openai_client.OaiResponseFormat
import software.medusa.commons.openai_client.OaiTargetedClient
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.filesystem.readText
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsLeaderTaskCompleter
import software.medusa.flow.harness.HrsProperTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.Observer
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsAiPatchInterpreter
import software.medusa.flow.harness.ai_system.HrsAiScoutDecisionInterpreter
import software.medusa.flow.harness.ai_system.HrsProperExpertAiSystem
import software.medusa.flow.harness.ai_system.HrsProperFrontlineAiSystem
import software.medusa.flow.harness.assistance.HrsProperAssistant
import software.medusa.flow.harness.assistance.HrsProperToolbox
import software.medusa.flow.harness.assistance.HrsToolboxFactory
import software.medusa.flow.harness.leadership.HrsProperLeader
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader

/**
 * The M3-10 parity run: the same M1/M2 demo-fixture task (change a greeting from `Hello` to
 * `Goodbye`, requiring a coherent two-file edit — the same fixture shape as `e2e`'s
 * `LoopFixture.gradle`, inlined here rather than shared across modules since `engine:harness`
 * cannot depend on `e2e`) run to completion on both the **builtin** and **leader/assistant**
 * engines against real models, recording outcome, wall time, and per-role token usage for each, and
 * asserting the leader/assistant engine's own token-ratio sanity check (leader ≪ assistant volume).
 *
 * This is a lightweight, in-process comparison (an in-memory physical workspace and a
 * text-comparison gate standing in for the fixture's real Gradle `verifyGreeting` check), not the
 * full hermetic e2e loop (real worker subprocess, control plane, git remote) that `e2e`'s
 * `HermeticLoop_integrationTests`/`HermeticLoop_claude_integrationTests` already run for the
 * builtin/claude engines — that full-fidelity loop does not yet have a leader-engine leg (the
 * reconcile-driven pipeline always stamps `Engine.Claude` today; wiring a leader shadow session is
 * out of scope here). This test exists specifically to make the economics comparison cheap and fast
 * to run repeatedly, per this milestone's own framing ("evidence base for eventually promoting it
 * to primary-engine status").
 *
 * Gated on `OPENAI_API_KEY`; skipped when it is not set.
 */
class EnginesParity_integrationTests {
  private companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private val rootModulePath: UfsLiteralAbsolutePath =
        checkNotNull(UfsAbsolutePath.parse("/").toLiteral())

    private val greeterFileName = UfsName.Literal("Greeter.java")
    private val greetingCheckFileName = UfsName.Literal("GreetingCheck.java")

    private val greeterSource =
        """
        package com.example;

        public final class Greeter {
          public static final String GREETING = "Hello";

          private Greeter() {}
        }
        """
            .trimIndent()

    private val greetingCheckSource =
        """
        package com.example;

        public final class GreetingCheck {
          private static final String EXPECTED_GREETING = "Hello";

          private GreetingCheck() {}
        }
        """
            .trimIndent()

    private val fixtureFiles =
        mapOf(
            greeterFileName.content to greeterSource,
            greetingCheckFileName.content to greetingCheckSource,
        )

    private fun paragraph(
        text: String,
    ): MdChapter =
        MdChapter.leaf(
            title = MdInlineContent.of("Task"),
            element = MdElement(blocks = listOf(MdBlock.Paragraph.of(text = text))),
        )

    private val taskDescription =
        HrsTaskDescription(
            body =
                paragraph(
                    "The application greets with `Hello`. It should greet with `Goodbye` " +
                        "instead. Update `Greeter.GREETING` to `Goodbye`, and update " +
                        "`GreetingCheck.EXPECTED_GREETING` to match, so the two stay in sync.",
                ),
        )
  }

  /** Passes iff `Greeter.GREETING` and `GreetingCheck.EXPECTED_GREETING` are the same literal. */
  private class GreeterModuleConnection(
      private val physicalRootDirectory: UfsMutableDirectory,
  ) : UnpModuleConnection {
    override suspend fun bootstrap() = UnpModuleConnection.Result.Success

    override suspend fun normalize() = UnpModuleConnection.Result.Success

    override suspend fun analyze() = evaluate()

    override suspend fun test() = evaluate()

    private suspend fun evaluate(): UnpModuleConnection.Result =
        try {
          val greeting = extractQuoted(fileName = greeterFileName, fieldName = "GREETING")
          val expected =
              extractQuoted(fileName = greetingCheckFileName, fieldName = "EXPECTED_GREETING")

          if (greeting != null && greeting == expected) UnpModuleConnection.Result.Success
          else
              UnpModuleConnection.Result.Failure(
                  diagnosticOutput =
                      "Greeter.GREETING=$greeting but GreetingCheck.EXPECTED_GREETING=$expected " +
                          "-- they must match",
              )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          UnpModuleConnection.Result.Failure(diagnosticOutput = "check failed to run: ${e.message}")
        }

    private suspend fun extractQuoted(
        fileName: UfsName.Literal,
        fieldName: String,
    ): String? {
      val file = physicalRootDirectory.extract(name = fileName) as? UfsReadonlyFile ?: return null
      return Regex("""$fieldName\s*=\s*"([^"]*)"""").find(file.readText())?.groupValues?.get(1)
    }
  }

  private object GreeterProjectManifestLoader : UnpProjectManifestLoader {
    override suspend fun load(
        projectDirectory: UfsReadonlyDirectory,
    ): UnpProjectManifest =
        UnpProjectManifest(
            moduleManifestByPath =
                mapOf(
                    rootModulePath to
                        object : UnpModuleManifest {
                          override suspend fun connect(
                              physicalWorkspace: PhwWorkspace,
                              modulePath: UfsLiteralAbsolutePath,
                          ): UnpModuleConnection =
                              GreeterModuleConnection(
                                  physicalRootDirectory = physicalWorkspace.rootDirectory,
                              )
                        },
                ),
        )
  }

  /** One engine's run: its outcome, wall time, and every role's token tally. */
  private data class EngineRun(
      val engineName: String,
      val outcome: TaskCompletionResult,
      val wallTimeMs: Long,
      val tallies: List<RoleTokenTally>,
  )

  private suspend fun runBuiltin(
      targetedClient: OaiTargetedClient,
  ): EngineRun {
    val frontlineTally = RoleTokenTally(roleName = "builtin_frontline")
    val expertTally = RoleTokenTally(roleName = "builtin_expert")

    fun frontlineTierClient(
        responseFormat: OaiResponseFormat = OaiResponseFormat.Text,
    ) =
        TallyingOaiConfiguredClient(
            delegate =
                targetedClient.configured(
                    model = OaiModel.GptMini,
                    responseFormat = responseFormat,
                ),
            tally = frontlineTally,
        )

    val taskCompleter =
        HrsProperTaskCompleter(
            physicalWorkspaceAllocator = InMemoryPhwWorkspaceAllocator(),
            projectManifestLoader = GreeterProjectManifestLoader,
            frontlineAiSystem = HrsProperFrontlineAiSystem(openaiClient = frontlineTierClient()),
            scoutDecisionInterpreter =
                HrsAiScoutDecisionInterpreter(
                    openaiClient =
                        frontlineTierClient(HrsAiScoutDecisionInterpreter.responseFormat),
                ),
            patchInterpreter =
                HrsAiPatchInterpreter(
                    openaiClient = frontlineTierClient(HrsAiPatchInterpreter.responseFormat),
                ),
            expertAiSystem =
                HrsProperExpertAiSystem(
                    openaiClient =
                        TallyingOaiConfiguredClient(
                            delegate = targetedClient.configured(model = OaiModel.GptMini),
                            tally = expertTally,
                        ),
                ),
        )

    val startedAt = System.currentTimeMillis()

    val outcome =
        withGitWorktree(files = fixtureFiles) { gitWorktree ->
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
              observer = Observer.Noop,
          )
        }

    val wallTimeMs = System.currentTimeMillis() - startedAt

    (outcome as? TaskCompletionResult.Success)?.temporaryWorkspace?.close()

    return EngineRun(
        engineName = "builtin",
        outcome = outcome,
        wallTimeMs = wallTimeMs,
        tallies = listOf(frontlineTally, expertTally),
    )
  }

  private suspend fun runLeader(
      targetedClient: OaiTargetedClient,
  ): EngineRun {
    val leaderTally = RoleTokenTally(roleName = "leader")
    val assistantTally = RoleTokenTally(roleName = "assistant")

    val leader =
        HrsProperLeader(
            openaiClient =
                TallyingOaiConfiguredClient(
                    delegate =
                        targetedClient.configured(
                            model = OaiModel.GptMini,
                            responseFormat = HrsProperLeader.responseFormat,
                        ),
                    tally = leaderTally,
                ),
        )

    val assistant =
        HrsProperAssistant(
            openaiClient =
                TallyingOaiConfiguredClient(
                    delegate =
                        targetedClient.configured(
                            model = OaiModel.GptMini,
                            toolDefinitions = HrsProperToolbox.toolDefinitions,
                        ),
                    tally = assistantTally,
                ),
            chunkSummaryClient = null,
        )

    val taskCompleter =
        HrsLeaderTaskCompleter(
            physicalWorkspaceAllocator = InMemoryPhwWorkspaceAllocator(),
            projectManifestLoader = GreeterProjectManifestLoader,
            leader = leader,
            assistant = assistant,
            toolboxFactory =
                HrsToolboxFactory {
                    gitWorktree,
                    physicalRootDirectory,
                    projectConnection,
                    delegationTimestamp,
                  ->
                  HrsProperToolbox(
                      gitWorktree = gitWorktree,
                      physicalRootDirectory = physicalRootDirectory,
                      projectConnection = projectConnection,
                      delegationTimestamp = delegationTimestamp,
                  )
                },
            maxDelegations = 10,
        )

    val startedAt = System.currentTimeMillis()

    val outcome =
        withGitWorktree(files = fixtureFiles) { gitWorktree ->
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription = taskDescription,
              observer = Observer.Noop,
          )
        }

    val wallTimeMs = System.currentTimeMillis() - startedAt

    (outcome as? TaskCompletionResult.Success)?.temporaryWorkspace?.close()

    return EngineRun(
        engineName = "leader",
        outcome = outcome,
        wallTimeMs = wallTimeMs,
        tallies = listOf(leaderTally, assistantTally),
    )
  }

  @Test
  fun `builtin and leader engines both complete the greeting task, with a token-ratio sanity check`() =
      runBlocking {
        assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

        val targetedClient =
            OaiProperClient.targeting(
                targetBaseUrl = OaiFreeClient.openAiBaseUrl,
                targetApiKey = OaiApiKey(content = checkNotNull(apiKey)),
            )

        // Sequential, not parallel: the leader/assistant client wiring is fine either way, but a
        // failure in one run should never race a partially-run second one in the failure output.
        val builtinRun = runBuiltin(targetedClient)
        val leaderRun = runLeader(targetedClient)

        for (run in listOf(builtinRun, leaderRun)) {
          val outcomeLabel =
              if (run.outcome is TaskCompletionResult.Success) "success" else "failure"
          println(
              "PARITY_RESULT fixture=greeting engine=${run.engineName} outcome=$outcomeLabel " +
                  "wallTimeMs=${run.wallTimeMs}",
          )
          run.tallies.forEach { tally ->
            println("PARITY_COST fixture=greeting engine=${run.engineName} ${tally.logLine()}")
          }
        }

        assertIs<TaskCompletionResult.Success>(
            builtinRun.outcome,
            "builtin run: ${builtinRun.outcome}",
        )
        assertIs<TaskCompletionResult.Success>(
            leaderRun.outcome,
            "leader run: ${leaderRun.outcome}",
        )

        val leaderTally = leaderRun.tallies.single { it.roleName == "leader" }
        val assistantTally = leaderRun.tallies.single { it.roleName == "assistant" }

        // The token-ratio sanity check (M3-10's acceptance criterion): the leader is the
        // expensive-model role but should be called far less than the cheap-model assistant.
        assertTrue(
            leaderTally.totalTokens < assistantTally.totalTokens,
            "expected leader token volume to stay far below assistant volume (leader=" +
                "${leaderTally.totalTokens}, assistant=${assistantTally.totalTokens})",
        )
      }
}
