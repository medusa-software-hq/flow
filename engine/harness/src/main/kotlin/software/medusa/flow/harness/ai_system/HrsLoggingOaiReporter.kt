package software.medusa.flow.harness.ai_system

import java.util.logging.Level
import java.util.logging.Logger
import software.medusa.commons.openai_client.OaiReporter

/**
 * The harness's shared [OaiReporter]: the 0.2.0 `openai-client` folds unexpected, critical
 * conditions it runs into (no choices, an empty or malformed response, a client/IO failure, a
 * malformed tool call) into a coarse [software.medusa.commons.openai_client.OaiResult] /
 * [software.medusa.commons.openai_client.OaiResponse] and hands the underlying detail to a reporter
 * instead of baking it into the value. This logs each such issue so it is not silently swallowed.
 *
 * Uses [java.util.logging] rather than a bespoke sink so the harness needs no extra logging
 * dependency; wire one instance at each composition root and pass it to
 * [software.medusa.commons.openai_client.OaiProperClient.targeting].
 */
class HrsLoggingOaiReporter(
    private val logger: Logger = Logger.getLogger("software.medusa.flow.harness.openai"),
) : OaiReporter {
  override fun reportNoChoices() {
    logger.warning("OpenAI response contained no choices")
  }

  override fun reportMultipleChoices(
      choiceCount: Int,
  ) {
    logger.warning("OpenAI response contained multiple choices ($choiceCount); using the first")
  }

  override fun reportMissingTokenUsage() {
    logger.warning("OpenAI response did not include token usage")
  }

  override fun reportEmptyResponse() {
    logger.warning("OpenAI response choice contained neither content nor tool calls")
  }

  override fun reportUnknownFinishReason(
      finishReason: String,
  ) {
    logger.warning("OpenAI reported an unrecognized finish reason: $finishReason")
  }

  override fun reportInvalidToolName(
      rawToolName: String,
      cause: Throwable,
  ) {
    logger.log(Level.WARNING, "OpenAI returned an invalid tool name: $rawToolName", cause)
  }

  override fun reportMalformedToolCallArguments(
      rawArguments: String,
      cause: Throwable,
  ) {
    logger.log(Level.WARNING, "OpenAI returned malformed tool-call arguments: $rawArguments", cause)
  }

  override fun reportIoError(
      cause: Throwable,
  ) {
    logger.log(Level.WARNING, "Network I/O error during chat completion", cause)
  }

  override fun reportClientError(
      cause: Throwable,
  ) {
    logger.log(Level.WARNING, "OpenAI client error during chat completion", cause)
  }
}
