package software.medusa.flow.core_service.worker.code_project.tools

import software.medusa.commons.paths.AbsoluteUnixPath
import software.medusa.commons.paths.LiteralAbsoluteUnixPath
import software.medusa.commons.paths.toLiteral
import software.medusa.openai_client.OpenAiChat
import software.medusa.openai_client.OpenAiClient
import software.medusa.openai_client.OpenAiMessage
import software.medusa.openai_client.OpenAiModel
import software.medusa.openai_client.OpenAiRole

data class ParsedAiIssueLine(
    val filePath: LiteralAbsoluteUnixPath,
    val info: String,
)

abstract class AbstractAiIssueOutputParser(
    private val openAiClient: OpenAiClient,
) {
  companion object {
    const val issuesKeyword = "ISSUES"
    const val abortKeyword = "ABORT"
  }

  protected abstract val systemPrompt: String

  protected abstract val positiveExampleInput: String

  protected abstract val positiveExampleOutput: String

  protected abstract val negativeExampleInput: String

  protected abstract val negativeExampleOutput: String

  protected open val model: OpenAiModel = OpenAiModel.Gemma4B

  protected suspend fun parseIssueLines(output: String): List<ParsedAiIssueLine>? {
    val input =
        OpenAiChat(
            messages =
                listOf(
                    OpenAiMessage(
                        role = OpenAiRole.System,
                        text = systemPrompt,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = positiveExampleInput,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = positiveExampleOutput,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.User,
                        text = negativeExampleInput,
                    ),
                    OpenAiMessage(
                        role = OpenAiRole.Assistant,
                        text = negativeExampleOutput,
                    ),
                    OpenAiMessage(role = OpenAiRole.User, text = output),
                ),
        )

    val responseText =
        openAiClient
            .createUnstructuredCompletion(
                request =
                    OpenAiClient.CompletionRequest(
                        input = input,
                        model = model,
                    ),
            )
            .responseText

    val responseLines = responseText.lines()
    val firstLine =
        responseLines.firstOrNull() ?: throw IllegalStateException("AI response is empty")

    when (firstLine) {
      issuesKeyword -> Unit
      abortKeyword -> return null
      else ->
          throw IllegalStateException(
              "Unexpected AI response format. First line should be either $issuesKeyword or $abortKeyword. Actual response:\n$responseText",
          )
    }

    val remainingLines = responseLines.drop(1)

    if (remainingLines.isEmpty()) {
      throw IllegalStateException(
          "AI response indicates issues were found, but no issue lines are present. Response:\n$responseText",
      )
    }

    return remainingLines.map { issueLineText ->
      val firstPart = issueLineText.substringBefore(" ")
      val filePath =
          AbsoluteUnixPath.parse(firstPart).toLiteral()
              ?: throw IllegalStateException(
                  "File path in AI response is not a valid absolute Unix path: $firstPart"
              )

      ParsedAiIssueLine(
          filePath = filePath,
          info = issueLineText.substringAfter(" "),
      )
    }
  }
}
