package software.medusa.flow.core_service.worker.ai_code_engineer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.luaj.vm2.Globals
import org.luaj.vm2.LoadState
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.MathLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import software.medusa.commons.paths.LiteralRelativeUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionInstructions
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.EditionScope
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.MaskedCodeFileContent
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.MaskedCodeFileContent.ContentBlock
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.Patch
import software.medusa.flow.core_service.worker.ai_code_engineer.AiCodeEditor.PatchSet
import software.medusa.flow.core_service.worker.code.CodeBlock
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndex
import software.medusa.flow.core_service.worker.code.CodeBlock.LineIndexRange
import software.medusa.flow.core_service.worker.code.CodeFileContent
import software.medusa.openai_client.OpenAiClient

class ProperAiCodeEditor_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENAI_API_KEY"

    private val apiKey =
        System.getenv(apiKeyEnvVarName)
            ?: error("Environment variable $apiKeyEnvVarName is not set")

    private fun buildOpenAiClient() =
        OpenAiClient.build(
            config =
                OpenAiClient.Config(
                    baseUrl = OpenAiClient.openAiBaseUrl,
                    apiKey = apiKey,
                ),
        )

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
  }

  @Test
  fun test_hello() = runTest {
    val openAiClient = buildOpenAiClient()

    val aiCodeEditor =
        ProperAiCodeEditor(
            openAiClient = openAiClient,
        )

    val bazFilePath =
        LiteralRelativeUnixPath.of(
            UnixPath.Name.Literal("foo"),
            UnixPath.Name.Literal("bar"),
            UnixPath.Name.Literal("baz.hello"),
        )

    val generatedPatchSet =
        aiCodeEditor.generateEditionPatchSet(
            editionInstructions =
                EditionInstructions(
                    instructions =
                        CodeBlock.of(
                            "Make the `say_hello` function say `HELLO!!!`.",
                        ),
                ),
            editionScope =
                EditionScope(
                    maskedCodeFileContentByPath =
                        mapOf(
                            bazFilePath to
                                MaskedCodeFileContent(
                                    blocks =
                                        listOf(
                                            ContentBlock(
                                                startIndex = LineIndex.ofOneBased(1),
                                                content =
                                                    CodeBlock.of(
                                                        /* 1 */ "#!/bin/hello",
                                                        /* 2 */ "",
                                                        /* 3 */ "%def say_hello [] => %say 'Hello.'",
                                                        /* 4 */ "",
                                                        /* 5 */ "%def say_bye [] => %say 'Bye.'",
                                                        /* 6 */ "",
                                                    ),
                                            ),
                                            MaskedCodeFileContent.MaskBlock(
                                                summary =
                                                    CodeBlock.of(
                                                        "A few irrelevant function definitions",
                                                    ),
                                            ),
                                            ContentBlock(
                                                startIndex = LineIndex.ofOneBased(32),
                                                content =
                                                    CodeBlock.of(
                                                        /* 32 */ "",
                                                        /* 33 */ "%def main [] => say_hello[]",
                                                    ),
                                            ),
                                        ),
                                ),
                        ),
                ),
        )

    assertEquals(
        expected =
            PatchSet(
                patchByFilePath =
                    mapOf(
                        bazFilePath to
                            Patch(
                                fragmentByOldLineIndexRange =
                                    mapOf(
                                        LineIndexRange(
                                            startIndex = LineIndex.ofOneBased(3),
                                            endIndexExclusive = LineIndex.ofOneBased(4),
                                        ) to
                                            Patch.Fragment(
                                                CodeBlock.of(
                                                    "%def say_hello [] => %say 'HELLO!!!'",
                                                ),
                                            ),
                                    ),
                            ),
                    ),
            ),
        actual = generatedPatchSet,
    )
  }

  @Test
  fun test_lua_fib() = runTest {
    val fibLuaText =
        """
        local function fib(n)
            if n < 1 then
                return n
            end
            
            return fib(n - 1) + fib(n + 1)
        end

        return fib(7)
        """
            .trimIndent()

    val fibLuaContent = CodeFileContent.parse(fibLuaText)

    val openAiClient = buildOpenAiClient()

    val aiCodeEditor =
        ProperAiCodeEditor(
            openAiClient = openAiClient,
        )

    val fibLuaFilePath =
        LiteralRelativeUnixPath.of(
            UnixPath.Name.Literal("fib.lua"),
        )

    val generatedPatchSet =
        aiCodeEditor.generateEditionPatchSet(
            editionInstructions =
                EditionInstructions(
                    instructions =
                        CodeBlock.of(
                            "Fix the Fibonacci function implementation.",
                        ),
                ),
            editionScope =
                EditionScope(
                    maskedCodeFileContentByPath =
                        mapOf(
                            fibLuaFilePath to
                                MaskedCodeFileContent.of(
                                    fileContent = fibLuaContent,
                                ),
                        ),
                ),
        )

    val fibLuaPatch =
        assertNotNull(
            generatedPatchSet.patchByFilePath[fibLuaFilePath],
        )

    val patchedFibLuaText = fibLuaContent.applyPatch(fibLuaPatch).dump()

    val luaGlobals = buildLuaGlobals()

    val result =
        luaGlobals
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
}
