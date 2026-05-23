package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import software.medusa.commons.paths.RelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.ChangeSet.Change
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeCatalog
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodePatcher.MaskedCodeFileContent
import software.medusa.commons.filesystem.tech.TechFileContent
import software.medusa.flow.core_service.worker.code.applyChange
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool
import software.medusa.flow.core_service.worker.code_project.tools.CodeTool.CodeModuleDiagnosis
import software.medusa.openai_client.OpenAiClient

class JsonAiCodePatcher_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")
      val actualApiKey = checkNotNull(apiKey)

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openAiBaseUrl,
                  apiKey = actualApiKey,
              ),
      )
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

    private val fibLuaContent =
        TechFileContent.Code.parse(
            """
            local function fib(n)
                if n <= 1 then
                    return n
                end

                return fib(n - 1) + fib(n + 1)
            end

            return fib(7)
            """
                .trimIndent(),
        )

    private val fibLuaFilePath =
        RelativeUnixPath.of(
            UnixPath.Name.Literal("fib.lua"),
        )

    private val maskedCodeCatalog =
        MaskedCodeCatalog(
            maskedCodeFileContentByPath =
                mapOf(
                    fibLuaFilePath to
                        MaskedCodeFileContent(
                            codeFileContent = fibLuaContent,
                            mask = MaskedCodeFileContent.Mask.Empty,
                        ),
                ),
        )
  }

  @Test
  fun test_patchToCompleteTask() = runTest {
    val patcher = JsonAiCodePatcher(openAiClient = buildClient())

    val patchSet =
        patcher
            .patchToCompleteTask(
                taskDescription = "Fix the Fibonacci function implementation.",
            )
            .generateChanges(
                maskedCodeCatalog = maskedCodeCatalog,
            )

    val fibLuaPatch =
        assertNotNull(
            patchSet.changeByFilePath[fibLuaFilePath] as? Change.Patch,
        )

    val patchedFibLuaText = fibLuaContent.applyChange(fibLuaPatch).dump()

    val result =
        buildLuaGlobals()
            .load(
                patchedFibLuaText,
            )
            .call()
            .toint()

    assertEquals(
        expected = 13,
        actual = result,
    )
  }

  @Test
  fun test_patchToFixIssues() = runTest {
    val patcher = JsonAiCodePatcher(openAiClient = buildClient())

    val patchSet =
        patcher
            .patchToFixIssues(
                originalTaskDescription = "Implement Fibonacci correctly.",
                moduleDiagnosis =
                    CodeModuleDiagnosis.Incorrect(
                        diagnosisByFilePath =
                            mapOf(
                                fibLuaFilePath to
                                    CodeTool.CodeFileDiagnosis(
                                        issues =
                                            listOf(
                                                CodeTool.CodeFileDiagnosis.Issue(
                                                    "The recursion uses n + 1 instead of decreasing toward the base case.",
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            )
            .generateChanges(
                maskedCodeCatalog = maskedCodeCatalog,
            )

    val fibLuaPatch =
        assertNotNull(
            patchSet.changeByFilePath[fibLuaFilePath] as? Change.Patch,
        )

    val patchedFibLuaText = fibLuaContent.applyChange(patch = fibLuaPatch).dump()

    val result = buildLuaGlobals().load(patchedFibLuaText).call().toint()

    assertEquals(
        expected = 13,
        actual = result,
    )
  }
}
