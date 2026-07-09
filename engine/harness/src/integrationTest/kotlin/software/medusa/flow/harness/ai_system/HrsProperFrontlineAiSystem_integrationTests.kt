package software.medusa.flow.harness.ai_system

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
import software.medusa.commons.git.worktree.GitWorktreeEntity
import software.medusa.commons.markdown.MdBlock
import software.medusa.commons.markdown.MdElement
import software.medusa.commons.openai_client.OaiApiKey
import software.medusa.commons.openai_client.OaiConfiguredClient
import software.medusa.commons.openai_client.OaiModel
import software.medusa.commons.openai_client.OaiProperClient
import software.medusa.commons.text.TxtBlock
import software.medusa.commons.text.TxtFileContent
import software.medusa.commons.unix.path.UfsName
import software.medusa.flow.harness.HrsTaskCompleter
import software.medusa.flow.harness.HrsTaskCompleter.SolutionImplementationObserver
import software.medusa.flow.harness.HrsTaskDescription
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutMessage
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.ScoutingLog
import software.medusa.flow.harness.ai_system.HrsFrontlineAiSystem.SolutionImplementationLog
import software.medusa.flow.harness.ai_system.HrsScoutDecisionInterpreter.Decision
import software.medusa.flow.virtual_editor.VedTimestamp
import software.medusa.flow.virtual_editor.worktree.VedClosedFile
import software.medusa.flow.virtual_editor.worktree.VedExpandedDirectory
import software.medusa.flow.virtual_editor.worktree.VedOpenedFile
import software.medusa.flow.virtual_editor.worktree.VedWorktree
import software.medusa.flow.virtual_editor.worktree_adjustment.VedFileAdjustment

/**
 * End-to-end checks of [HrsProperFrontlineAiSystem] against a real model, exercising its two phases
 * independently:
 *
 * - [HrsProperFrontlineAiSystem.performScouting]: given a worktree whose only file is still closed
 *   and a task that clearly concerns it, the Scout (interpreted into a structured adjustment) must
 *   ask for that file to be opened.
 * - [HrsProperFrontlineAiSystem.implementSolution]: given a worktree with a deliberately broken
 *   Fibonacci program (the recursion adds `fib(n + 1)` instead of `fib(n - 2)`), the Coder
 *   (interpreted into a real patch) must fix it; the patched program is then executed in an
 *   embedded Lua interpreter and is considered functional only if it computes `fib(7) == 13`.
 *
 * Gated on `OPENAI_API_KEY`; skipped when it is not set.
 */
class HrsProperFrontlineAiSystem_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private fun buildClient(): OaiConfiguredClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

      return OaiProperClient.withTarget(
              targetBaseUrl = OaiConfiguredClient.openAiBaseUrl,
              targetApiKey = OaiApiKey(content = checkNotNull(apiKey)),
          )
          .withModel(model = OaiModel.GptMini)
    }

    private fun buildAiSystem(
        client: OaiConfiguredClient,
    ): HrsProperFrontlineAiSystem = HrsProperFrontlineAiSystem(openaiClient = client)

    private val silentScoutingObserver =
        object : HrsTaskCompleter.ScoutingObserver {
          override fun observeRound(
              roundNumber: Int,
              baseEditorWorktree: VedWorktree,
              scoutMessage: ScoutMessage,
          ) = Unit

          override fun observeRawResponse(
              response: OaiConfiguredClient.UnstructuredCompletionResponse,
          ) = Unit
        }

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

    private val fibFileName = UfsName.Literal("fib.lua")

    private fun paragraph(
        text: String,
    ): MdElement = MdElement(blocks = listOf(MdBlock.Paragraph.of(text = text)))

    private fun openedWorktreeOf(
        luaSource: String,
    ): VedWorktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory(
                    labeledEntityByName =
                        mapOf(
                            fibFileName to
                                VedExpandedDirectory.LabeledEntity(
                                    status = GitWorktreeEntity.Status.included,
                                    entity =
                                        VedOpenedFile.of(
                                            content =
                                                TxtFileContent(content = TxtBlock.parse(luaSource)),
                                            timestamp = VedTimestamp.zero,
                                        ),
                                ),
                        ),
                ),
        )

    private fun closedWorktreeOf(): VedWorktree =
        VedWorktree(
            rootDirectory =
                VedExpandedDirectory(
                    labeledEntityByName =
                        mapOf(
                            fibFileName to
                                VedExpandedDirectory.LabeledEntity(
                                    status = GitWorktreeEntity.Status.included,
                                    entity = VedClosedFile,
                                ),
                        ),
                ),
        )
  }

  @Test
  fun test_performScouting_requestsOpeningTheRelevantFile() = runBlocking {
    val client = buildClient()
    val frontlineAiSystem = buildAiSystem(client)

    val closedWorktree = closedWorktreeOf()

    val scoutMessage =
        frontlineAiSystem.performScouting(
            taskDescription =
                HrsTaskDescription(
                    body =
                        paragraph(
                            "The `fib.lua` program is supposed to compute the 7th Fibonacci " +
                                "number, but it is broken. Fix it so that it returns the correct " +
                                "value.",
                        ),
                ),
            editorWorktree = closedWorktree,
            scoutingLog = ScoutingLog.empty,
            scoutingObserver = silentScoutingObserver,
        )

    val decision =
        HrsAiScoutDecisionInterpreter(openaiClient = client)
            .interpretDecision(scoutMessage = scoutMessage, editorWorktree = closedWorktree)

    val continueDecision = assertIs<Decision.Continue>(decision)

    val rootDirectoryAdjustment = continueDecision.requestedAdjustment.rootDirectoryAdjustment

    assertEquals(
        expected = VedFileAdjustment.Open,
        actual = rootDirectoryAdjustment.childAdjustmentByName[fibFileName],
    )
  }

  @Test
  fun test_implementSolution_fixesLuaProgram() = runBlocking {
    val client = buildClient()
    val frontlineAiSystem = buildAiSystem(client)

    val baseWorktree = openedWorktreeOf(buggyFibLua)

    val patchMessage =
        frontlineAiSystem.implementSolution(
            taskDescription =
                HrsTaskDescription(
                    body =
                        paragraph(
                            "The `fib.lua` program is supposed to compute the 7th Fibonacci " +
                                "number, but it is broken. Fix it so that it returns the correct " +
                                "value.",
                        ),
                ),
            editorWorktree = baseWorktree,
            implementationPlan = HrsExpertAiSystem.ImplementationPlan(body = ""),
            solutionImplementationLog = SolutionImplementationLog.empty,
            solutionImplementationObserver = SolutionImplementationObserver.Noop,
        )

    val solutionPatch =
        HrsAiPatchInterpreter(openaiClient = client)
            .interpretPatch(patchMessage = patchMessage, editorWorktree = baseWorktree)

    val finalWorktree =
        solutionPatch
            .patchWorktree(worktree = baseWorktree, timestamp = VedTimestamp.zero.next)
            .patchedWorktree

    val fixedFile =
        finalWorktree.rootDirectory.labeledEntityByName.getValue(fibFileName).entity
            as VedOpenedFile

    val fixedLuaText = fixedFile.currentContent.dump()

    val result = buildLuaGlobals().load(fixedLuaText).call().toint()

    println("Result: $result")

    assertEquals(
        expected = 13,
        actual = result,
    )
  }
}
