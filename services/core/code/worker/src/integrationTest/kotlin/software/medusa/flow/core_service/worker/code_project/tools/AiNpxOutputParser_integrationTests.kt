package software.medusa.flow.core_service.worker.code_project.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.openai_client.OpenAiClient

private val exampleNpxOutput =
    """
    src/index.ts:1:7 - error TS2322: Type 'number' is not assignable to type 'string'.

    1 const answer: string = 42
            ~~~~~~

    Found 1 error in /var/code/src/index.ts:1
    """
        .trimIndent()

private const val exampleNpxOutputWithoutFileIssues =
    "This is not the tsc command you are looking for"

class AiNpxOutputParser_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENROUTER_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openRouterBaseUrl,
                  apiKey = checkNotNull(apiKey),
              ),
      )
    }
  }

  @Test
  fun test_parse_withFileIssues() = runTest {
    val parser = AiNpxOutputParser(openAiClient = buildClient())

    val parsed = parser.parse(exampleNpxOutput)

    assertEquals(
        expected =
            NpxOutputParser.ParsedNpxOutput.Issues(
                issues =
                    listOf(
                        NpxOutputParser.NpxIssue(
                            filePath =
                                AbsoluteUnixPath.of(
                                    UnixPath.Name.Literal("var"),
                                    UnixPath.Name.Literal("code"),
                                    UnixPath.Name.Literal("src"),
                                    UnixPath.Name.Literal("index.ts"),
                                ),
                            info =
                                "[1:7] error TS2322: Type 'number' is not assignable to type 'string'.",
                        ),
                    ),
            ),
        actual = parsed,
    )
  }

  @Test
  fun test_parse_withoutFileIssues() = runTest {
    val parser = AiNpxOutputParser(openAiClient = buildClient())

    val parsed = parser.parse(exampleNpxOutputWithoutFileIssues)

    assertEquals(
        expected = NpxOutputParser.ParsedNpxOutput.Error,
        actual = parsed,
    )
  }
}
