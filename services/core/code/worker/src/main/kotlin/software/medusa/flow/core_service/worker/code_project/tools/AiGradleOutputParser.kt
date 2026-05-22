package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.flow.core_service.worker.code_project.tools.GradleOutputParser.GradleIssue
import software.medusa.openai_client.OpenAiClient

private const val systemPrompt =
    """You are a parser for Gradle build output. This is an automated workflow.

The user message contains only raw Gradle output.

Extract only file-specific errors or warnings and output them in this exact format:

${AbstractAiIssueOutputParser.issuesKeyword}
/path/to/file1.ext [line:column] message
/path/to/file2.ext [line:column] message

Rules:
- Output only plain text.
- One issue per line.
- Include only issues that clearly reference a specific file.

If no file-specific issues are present, or the task can't be solved for any other reason, say: ${AbstractAiIssueOutputParser.abortKeyword}
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
    """${AbstractAiIssueOutputParser.issuesKeyword}
/Users/joe/example/App.kt [19:17] Unresolved reference 'asd'.
"""

private const val exampleGradleOutput2 =
    """> Task :worker:compileTestKotlin FAILED
[Incubating] Problems report is available at: file:///Users/joe/example/problems-report.html
FAILURE: Build failed with an exception.
BUILD FAILED in 492ms
22 actionable tasks: 1 executed, 21 up-to-date
"""

private const val exampleParsedOutput2 = "${AbstractAiIssueOutputParser.abortKeyword}\n"

class AiGradleOutputParser(
    openAiClient: OpenAiClient,
) :
    AbstractAiIssueOutputParser(
        openAiClient = openAiClient,
    ),
    GradleOutputParser {
  override val systemPrompt: String =
      software.medusa.flow.core_service.worker.code_project.tools.systemPrompt

  override val positiveExampleInput: String = exampleGradleOutput1

  override val positiveExampleOutput: String = exampleParsedOutput1

  override val negativeExampleInput: String = exampleGradleOutput2

  override val negativeExampleOutput: String = exampleParsedOutput2

  override suspend fun parse(
      gradleOutput: String,
  ): GradleOutputParser.ParsedGradleOutput {
    val parsedIssueLines =
        parseIssueLines(output = gradleOutput) ?: return GradleOutputParser.ParsedGradleOutput.Error

    return GradleOutputParser.ParsedGradleOutput.Issues(
        issues =
            parsedIssueLines.map { parsedIssueLine ->
              GradleIssue(
                  filePath = parsedIssueLine.filePath,
                  info = parsedIssueLine.info,
              )
            },
    )
  }
}
