package software.medusa.flow.harness.leader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.markdown.MdInlineContent
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiFreeClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.unix.filesystem.UfsMutableDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyDirectory
import software.medusa.commons.unix.filesystem.UfsReadonlyFile
import software.medusa.commons.unix.filesystem.readText
import software.medusa.commons.unix.path.UfsAbsolutePath
import software.medusa.commons.unix.path.UfsAbsolutePath.Companion.toLiteral
import software.medusa.commons.unix.path.UfsLiteralAbsolutePath
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsLeaderTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.TaskCompletionResult
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.assistance.HrsProperAssistant
import software.medusa.flow.harness.assistance.HrsProperToolbox
import software.medusa.flow.harness.assistance.HrsToolboxFactory
import software.medusa.flow.harness.history.HrsDelegationReport
import software.medusa.flow.harness.leadership.HrsProperLeader
import software.medusa.flow.harness.leadership.HrsTaskDefinition
import software.medusa.flow.physical_workspace.PhwWorkspace
import software.medusa.flow.universal_project.UnpModuleConnection
import software.medusa.flow.universal_project.UnpModuleManifest
import software.medusa.flow.universal_project.UnpProjectManifest
import software.medusa.flow.universal_project.UnpProjectManifestLoader

/**
 * The leader/assistant engine's real-model integration test (M3-10): a small fixture (a single Lua
 * file with a deliberately broken Fibonacci recursion — using the "run the result" idea, also used
 * by [software.medusa.flow.harness.ai_system.HrsProperFrontlineAiSystem_integrationTests] for the
 * builtin engine) and a task asking for it to be fixed, run through the *full*
 * [HrsLeaderTaskCompleter.completeTask] — manifest load, initial gate, the leader/assistant turn
 * grammar, chunk-summary bookkeeping, final gate — against real models.
 *
 * The manifest's gate ([LuaFibModuleConnection]) is itself a real Lua evaluation of whatever
 * `fib.lua` currently looks like in the physical workspace, so the engine's own stop condition is
 * meaningful (not rubber-stamped); [buildLuaGlobals] is invoked a second time, independently, after
 * the run to actually execute the model's final output rather than trust the gate.
 *
 * Fixture language: embedded Lua, not a real toolchain (Gradle/npm) — cheapest to maintain, since
 * it needs no manifest-driven build tool at all. The engine still requires *a* manifest, so this
 * test supplies its own minimal one whose module directly executes Lua as its check, rather than
 * pulling in a real project.yaml-backed toolchain.
 *
 * **Compaction (story 07)** is not exercised here: chunk boundaries are 3/8 delegations apart (see
 * [software.medusa.flow.harness.history.HrsChunkConfig.default]) and a trivial one-file fix
 * routinely finishes in fewer delegations than that, so forcing a real, paid run to cross one would
 * mean inflating the task well past what's needed to prove the engine works. It is exercised
 * instead as a unit-level rehearsal (fakes, no real model) in
 * [software.medusa.flow.harness.HrsLeaderTaskCompleter_tests] — see its chunk-close tests.
 *
 * Gated on `OPENAI_API_KEY`; skipped when it is not set.
 */
class HrsLeaderTaskCompleter_integrationTests {
  private companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private val rootModulePath: UfsLiteralAbsolutePath =
        checkNotNull(UfsAbsolutePath.parse("/").toLiteral())

    private val fibFileName = UfsName.Literal("fib.lua")

    private val buggyFibLua =
        """
        local function fib(n)
            if n <= 1 then
                return n
            end

            return fib(n - 1) + fib(n + 1)
        end

        return fib(7)
        """
            .trimIndent()

    private fun buildLuaGlobals(): Globals =
        Globals().apply {
          load(BaseLib())
          load(PackageLib())
          load(TableLib())
          load(StringLib())
          load(MathLib())

          LoadState.install(this)
          LuaC.install(this)
        }

    private fun paragraph(
        text: String,
    ): MdChapter =
        MdChapter.leaf(
            title = MdInlineContent.of("Task"),
            element = MdElement(blocks = listOf(MdBlock.Paragraph.of(text = text))),
        )
  }

  /** Evaluates `fib.lua` as it currently stands in the physical workspace as the module's gate. */
  private class LuaFibModuleConnection(
      private val physicalRootDirectory: UfsMutableDirectory,
  ) : UnpModuleConnection {
    override suspend fun bootstrap() = UnpModuleConnection.Result.Success

    override suspend fun normalize() = UnpModuleConnection.Result.Success

    override suspend fun analyze() = evaluate()

    override suspend fun test() = evaluate()

    private suspend fun evaluate(): UnpModuleConnection.Result =
        try {
          val fibFile =
              physicalRootDirectory.extract(name = fibFileName) as? UfsReadonlyFile
                  ?: return UnpModuleConnection.Result.Failure(
                      diagnosticOutput = "fib.lua is missing or is not a file",
                  )

          val computed = buildLuaGlobals().load(fibFile.readText()).call().toint()

          if (computed == 13) UnpModuleConnection.Result.Success
          else
              UnpModuleConnection.Result.Failure(
                  diagnosticOutput = "fib(7) evaluated to $computed, expected 13",
              )
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          UnpModuleConnection.Result.Failure(
              diagnosticOutput = "fib.lua failed to run: ${e.message}"
          )
        }
  }

  private object LuaFibProjectManifestLoader : UnpProjectManifestLoader {
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
                              LuaFibModuleConnection(
                                  physicalRootDirectory = physicalWorkspace.rootDirectory,
                              )
                        },
                ),
        )
  }

  /**
   * Records what the turn-grammar assertions below need: every delegation's report in order, and
   * any [observeAgentAction] fired while no delegation is open — which would mean the leader acted
   * directly instead of only ever deciding [HrsLeaderTaskCompleter] never actually lets that
   * happen, so this is a regression net, not a speculative check.
   */
  private class LeaderTurnGrammarObserver :
      HrsTaskCompleter.Observer by HrsTaskCompleter.Observer.Noop {
    val delegationReports = mutableListOf<HrsDelegationReport>()
    val leaderMicroMoves = mutableListOf<String>()

    private var delegationOpen = false

    override fun observeDelegationStarted(
        taskDefinition: HrsTaskDefinition,
    ) {
      delegationOpen = true
    }

    override fun observeDelegationReport(
        report: HrsDelegationReport,
    ) {
      delegationOpen = false
      delegationReports += report
    }

    override fun observeAgentAction(
        summary: String,
    ) {
      if (!delegationOpen) leaderMicroMoves += summary
    }
  }

  @Test
  fun `fixes the Lua fib program, staying inside the delegate-only turn grammar`() = runBlocking {
    assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

    val targetedClient =
        OaiProperClient.targeting(
            targetBaseUrl = OaiFreeClient.openAiBaseUrl,
            targetApiKey = OaiApiKey(content = checkNotNull(apiKey)),
        )

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
            projectManifestLoader = LuaFibProjectManifestLoader,
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
            // A generous but bounded budget: this fixture is trivial, so a well-behaved run
            // finishes
            // in a handful of delegations; this only guards against a real run going pathological.
            maxDelegations = 10,
        )

    val observer = LeaderTurnGrammarObserver()

    val result =
        withGitWorktree(files = mapOf("fib.lua" to buggyFibLua)) { gitWorktree ->
          taskCompleter.completeTask(
              sourceGitWorktree = gitWorktree,
              taskDescription =
                  HrsTaskDescription(
                      body =
                          paragraph(
                              "The `fib.lua` program is supposed to compute the 7th Fibonacci " +
                                  "number, but it is broken. Fix it so that it returns the " +
                                  "correct value.",
                          ),
                  ),
              observer = observer,
          )
        }

    val success = assertIs<TaskCompletionResult.Success>(result)

    // Run-the-result: an independent Lua evaluation of the model's actual final output, outside the
    // engine's own gate (which already ran the same check internally to decide when to stop).
    val finalFile =
        success.temporaryWorkspace.rootDirectory.extract(name = fibFileName) as UfsReadonlyFile
    val computedResult = buildLuaGlobals().load(finalFile.readText()).call().toint()

    success.temporaryWorkspace.close()

    assertEquals(
        expected = 13,
        actual = computedResult,
        message = "the patched fib.lua must actually compute fib(7) == 13",
    )

    // Turn grammar: the leader only ever decides (Delegate | Stop) -- every agent action observed
    // must fall inside a delegation window.
    assertTrue(
        observer.leaderMicroMoves.isEmpty(),
        "the leader must never act outside a delegation; saw: ${observer.leaderMicroMoves}",
    )

    assertTrue(observer.delegationReports.isNotEmpty(), "expected at least one delegation")

    val firstPatchIndex = observer.delegationReports.indexOfFirst { it.filesTouched.isNotBlank() }

    assertTrue(
        firstPatchIndex >= 0,
        "expected at least one delegation to report a touched file; reports were: " +
            "${observer.delegationReports}",
    )
    assertTrue(
        firstPatchIndex > 0,
        "expected the first delegation to be exploratory (no files touched yet); was: " +
            "${observer.delegationReports.first()}",
    )
    assertTrue(
        observer.delegationReports.subList(0, firstPatchIndex).any {
          it.bufferChanges.isNotBlank()
        },
        "expected the leader board's exposure buffer to be non-empty before the first " +
            "patch-bearing delegation",
    )

    println("LEADER_ENGINE_COST fixture=leader-fib ${leaderTally.logLine()}")
    println("LEADER_ENGINE_COST fixture=leader-fib ${assistantTally.logLine()}")

    // The token-ratio sanity check (M3-10's acceptance criterion): the leader is the
    // expensive-model
    // role but should be called far less than the cheap-model assistant, which does the actual
    // tool-calling work.
    assertTrue(
        leaderTally.totalTokens < assistantTally.totalTokens,
        "expected leader token volume to stay far below assistant volume (leader=" +
            "${leaderTally.totalTokens}, assistant=${assistantTally.totalTokens})",
    )
  }
}
