package software.medusa.flow.core_service.worker.code_project.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.UnixPath
import software.medusa.flow.core_service.worker.code_project.tools.GradleOutputParser.ParsedGradleOutput
import software.medusa.openai_client.OpenAiClient

private val exampleGradleOutput =
    """
    > Task :worker:compileTestKotlin FAILED
    e: file:///Users/jakub/Repositories/Medusa/flow/CodeProject_applyPatch_tests.kt:19:17 Unresolved reference 'asd'.
    [Incubating] Problems report is available at: file:///Users/jakub/Repositories/Medusa/flow/services/core/code/build/reports/problems/problems-report.html
    FAILURE: Build failed with an exception.
    * What went wrong:
    Execution failed for task ':worker:compileTestKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
       > Compilation error. See log for more details
    * Try:
    > Run with --stacktrace option to get the stack trace.
    > Run with --info or --debug option to get more log output.
    > Run with --scan to get full insights from a Build Scan (powered by Develocity).
    > Get more help at https://help.gradle.org.
    Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
    You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.
    For more on this, please refer to https://docs.gradle.org/9.4.0/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.
    BUILD FAILED in 492ms
    22 actionable tasks: 1 executed, 21 up-to-date
    """
        .trimIndent()

private val exampleGradleOutput2 =
    """
    > Task :worker:compileTestKotlin FAILED
    [Incubating] Problems report is available at: file:///Users/jakub/Repositories/Medusa/flow/services/core/code/build/reports/problems/problems-report.html
    FAILURE: Build failed with an exception.
    * What went wrong:
    Execution failed for task ':worker:compileTestKotlin'.
    > A failure occurred while executing org.jetbrains.kotlin.compilerRunner.btapi.BuildToolsApiCompilationWork
       > Compilation error. See log for more details
    * Try:
    > Run with --stacktrace option to get the stack trace.
    > Run with --info or --debug option to get more log output.
    > Run with --scan to get full insights from a Build Scan (powered by Develocity).
    > Get more help at https://help.gradle.org.
    Deprecated Gradle features were used in this build, making it incompatible with Gradle 10.
    You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.
    For more on this, please refer to https://docs.gradle.org/9.4.0/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.
    BUILD FAILED in 492ms
    22 actionable tasks: 1 executed, 21 up-to-date
    """
        .trimIndent()

class AiGradleOutputParser_integrationTests {
  companion object {
    private const val apiKeyEnvVarName = "OPENROUTER_API_KEY"

    private val apiKey = System.getenv(apiKeyEnvVarName)

    private fun buildClient(): OpenAiClient {
      assumeTrue(apiKey != null, "Environment variable $apiKeyEnvVarName is not set")

      val actualApiKey = checkNotNull(apiKey)

      return OpenAiClient.build(
          config =
              OpenAiClient.Config(
                  baseUrl = OpenAiClient.openRouterBaseUrl,
                  apiKey = actualApiKey,
              ),
      )
    }
  }

  @Test
  fun test_parse_withFileIssues() = runTest {
    val gradleOutputParser = AiGradleOutputParser(openAiClient = buildClient())

    val parsedGradleOutput = gradleOutputParser.parse(exampleGradleOutput)

    assertEquals(
        expected =
            ParsedGradleOutput.Issues(
                issues =
                    listOf(
                        GradleOutputParser.GradleIssue(
                            filePath =
                                AbsoluteUnixPath.of(
                                    UnixPath.Name.Literal("Users"),
                                    UnixPath.Name.Literal("jakub"),
                                    UnixPath.Name.Literal("Repositories"),
                                    UnixPath.Name.Literal("Medusa"),
                                    UnixPath.Name.Literal("flow"),
                                    UnixPath.Name.Literal("CodeProject_applyPatch_tests.kt"),
                                ),
                            info = "[19:17] Unresolved reference 'asd'.",
                        ),
                    ),
            ),
        actual = parsedGradleOutput,
    )
  }

  @Test
  fun test_patchToCompleteTask_withoutFileIssues() = runTest {
    val gradleOutputParser = AiGradleOutputParser(openAiClient = buildClient())

    val parsedGradleOutput = gradleOutputParser.parse(exampleGradleOutput2)

    assertEquals(
        expected = ParsedGradleOutput.Error,
        actual = parsedGradleOutput,
    )
  }
}
