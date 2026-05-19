package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.openai_client.OpenAiClient

private const val npxSystemPrompt =
    """You are a parser for command-line tool output produced by npx-run checks like TypeScript.

The user message contains only raw tool output.

Extract only file-specific errors or warnings and output them in this exact format:

${AbstractAiIssueOutputParser.issuesKeyword}
/path/to/file1.ext [line:column] message
/path/to/file2.ext [line:column] message

Rules:
- Output only plain text.
- One issue per line.
- Include only issues that clearly reference a specific file.
- If no file-specific issues are present, output exactly: ${AbstractAiIssueOutputParser.errorKeyword}
"""

private const val exampleNpxOutput1 =
    """src/index.ts:1:7 - error TS2322: Type 'number' is not assignable to type 'string'.

1 const answer: string = 42
        ~~~~~~

Found 1 error in src/index.ts:1
"""

private const val exampleParsedNpxOutput1 =
    """${AbstractAiIssueOutputParser.issuesKeyword}
/workspace/src/index.ts [1:7] error TS2322: Type 'number' is not assignable to type 'string'.
"""

private const val exampleNpxOutput2 =
    """This is not the tsc command you are looking for
"""

private const val exampleParsedNpxOutput2 = "${AbstractAiIssueOutputParser.errorKeyword}\n"

class AiNpxOutputParser(
    openAiClient: OpenAiClient,
) :
    AbstractAiIssueOutputParser(
        openAiClient = openAiClient,
    ),
    NpxOutputParser {
  override val systemPrompt: String = npxSystemPrompt

  override val positiveExampleInput: String = exampleNpxOutput1

  override val positiveExampleOutput: String = exampleParsedNpxOutput1

  override val negativeExampleInput: String = exampleNpxOutput2

  override val negativeExampleOutput: String = exampleParsedNpxOutput2

  override suspend fun parse(npxOutput: String): NpxOutputParser.ParsedNpxOutput {
    val parsedIssueLines =
        parseIssueLines(output = npxOutput) ?: return NpxOutputParser.ParsedNpxOutput.Error

    return NpxOutputParser.ParsedNpxOutput.Issues(
        issues =
            parsedIssueLines.map { parsedIssueLine ->
              NpxOutputParser.NpxIssue(
                  filePath = parsedIssueLine.filePath,
                  info = parsedIssueLine.info,
              )
            },
    )
  }
}
