package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.flow.core_service.worker.code_project.tools.GradleOutputParser.GradleIssue
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

private const val issuesKeyword = "ISSUES"
private const val errorKeyword = "ERROR"

private const val systemPrompt =
    """You are a parser for Gradle build output.

The user message contains only raw Gradle output.

Extract only file-specific errors or warnings and output them in this exact format:

$issuesKeyword
/path/to/file1.ext [line:column] message
/path/to/file2.ext [line:column] message

Rules:
- Output only plain text.
- One issue per line.
- Include only issues that clearly reference a specific file.
- If no file-specific issues are present, output exactly: ERROR
"""

private const val exampleGradleOutput1 =
    """> Task :worker:compileTestKotlin FAILED
e: file:///Users/joe/example/App.kt:19:17 Unresolved reference 'asd'.
[Incubating] Problems report is available at: file:///Users/joe/example/problems-report.html
FAILURE: Build failed with an exception.
BUILD FAILED in 492ms
22 actionable tasks: 1 executed, 21 up-to-date
"""

private const val exampleParsedOutput1 =
    """$issuesKeyword
/Users/joe/example/App.kt [19:17] Unresolved reference 'asd'.
"""

private const val exampleGradleOutput2 =
    """> Task :worker:compileTestKotlin FAILED
[Incubating] Problems report is available at: file:///Users/joe/example/problems-report.html
FAILURE: Build failed with an exception.
BUILD FAILED in 492ms
22 actionable tasks: 1 executed, 21 up-to-date
"""

private const val exampleParsedOutput2 = "$errorKeyword\n"

private val parsingModel = OpenAiModel.Gemma4B

class AiGradleOutputParser(
    private val openAiClient: OpenAiClient,
) : GradleOutputParser {

  override suspend fun parse(
      gradleOutput: String,
  ): GradleOutputParser.ParsedGradleOutput {
    val completionInput =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text = systemPrompt,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = exampleGradleOutput1,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = exampleParsedOutput1,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = exampleGradleOutput2,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = exampleParsedOutput2,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = gradleOutput,
                    ),
                ),
        )

    val responseText =
        openAiClient
            .createUnstructuredCompletion(
                request =
                    OpenAiClient.CompletionRequest(
                        input = completionInput,
                        model = parsingModel,
                    ),
            )
            .responseText

    val responseLines = responseText.lines()

    val firstLine =
        responseLines.firstOrNull() ?: throw IllegalStateException("AI response is empty")

    when (firstLine) {
      issuesKeyword -> {
        // Parse the issues
      }

      errorKeyword -> {
        return GradleOutputParser.ParsedGradleOutput.Error
      }

      else ->
          throw IllegalStateException(
              "Unexpected AI response format. First line should be either $issuesKeyword or $errorKeyword. Actual response:\n$responseText",
          )
    }

    val remainingLines = responseLines.drop(1)

    if (remainingLines.isEmpty()) {
      throw IllegalStateException(
          "AI response indicates issues were found, but no issue lines are present. Response:\n$responseText"
      )
    }

    val gradleIssues = remainingLines.map { issueLineText ->
      val firstPart = issueLineText.substringBefore(" ")
      val filePath =
          AbsoluteUnixPath.parse(firstPart).toLiteral()
              ?: throw IllegalStateException(
                  "File path in AI response is not a valid absolute Unix path: $firstPart"
              )

      val secondPart = issueLineText.substringAfter(" ")

      GradleIssue(
          filePath = filePath,
          info = secondPart,
      )
    }

    return GradleOutputParser.ParsedGradleOutput.Issues(
        issues = gradleIssues,
    )
  }
}
