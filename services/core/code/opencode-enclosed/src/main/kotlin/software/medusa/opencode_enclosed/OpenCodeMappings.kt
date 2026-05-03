package software.medusa.opencode_enclosed

import java.time.Instant
import software.medusa.opencode_client.OpenCodeMessage
import software.medusa.opencode_client.OpenCodeMessageError
import software.medusa.opencode_client.OpenCodeMessagePart
import software.medusa.opencode_client.OpenCodeStepTokens
import software.medusa.opencode_client.OpenCodeToolTime

fun OpenCodeMessage.toEnclosedMessage(): EnclosedMessage {
  val text =
      parts.filter { it.type == "text" }.mapNotNull(OpenCodeMessagePart::text).joinToString("")
  val toolCalls = buildEnclosedToolCalls(parts)
  val usage =
      parts
          .asReversed()
          .firstNotNullOfOrNull(OpenCodeMessagePart::tokens)
          ?.toEnclosedStepTokenStats()
  val estimatedCostUsd = parts.asReversed().firstNotNullOfOrNull(OpenCodeMessagePart::cost)

  return EnclosedMessage(
      id = id?.let(::EnclosedMessageId),
      role = role?.toEnclosedMessageRole(),
      createdAt = time?.created?.let(Instant::ofEpochMilli),
      completedAt = time?.completed?.let(Instant::ofEpochMilli),
      text = text,
      toolCalls = toolCalls,
      error = info?.error?.toEnclosedMessageError(),
      usage = usage,
      estimatedCostUsd = estimatedCostUsd,
  )
}

private fun buildEnclosedToolCalls(parts: List<OpenCodeMessagePart>): List<EnclosedToolCall> {
  val toolCallById = linkedMapOf<String, OpenCodeMessagePart>()

  for (part in parts) {
    val callId = part.callId ?: continue
    val toolName = part.tool ?: continue

    val previousPart = toolCallById[callId]
    toolCallById[callId] =
        if (previousPart == null) {
          part
        } else {
          mergeToolCallParts(previousPart, part)
        }
  }

  return toolCallById.map { (callId, part) ->
    val toolState = part.state

    EnclosedToolCall(
        id = EnclosedToolCallId(callId),
        toolName = EnclosedToolName(requireNotNull(part.tool)),
        title = toolState?.title,
        status = toolState?.status,
        inputJson = toolState?.input?.toString(),
        outputText = toolState?.output,
        errorText = toolState?.error,
        startedAt = toolState?.time?.start?.let(Instant::ofEpochMilli),
        endedAt = toolState?.time?.end?.let(Instant::ofEpochMilli),
    )
  }
}

private fun mergeToolCallParts(
    previousPart: OpenCodeMessagePart,
    nextPart: OpenCodeMessagePart,
): OpenCodeMessagePart {
  val previousState = previousPart.state
  val nextState = nextPart.state

  return nextPart.copy(
      tool = nextPart.tool ?: previousPart.tool,
      state =
          when {
            previousState == null -> nextState
            nextState == null -> previousState
            else ->
                nextState.copy(
                    status = nextState.status ?: previousState.status,
                    input = nextState.input ?: previousState.input,
                    output = nextState.output ?: previousState.output,
                    metadata = nextState.metadata ?: previousState.metadata,
                    title = nextState.title ?: previousState.title,
                    error = nextState.error ?: previousState.error,
                    time = mergeToolTime(previousState.time, nextState.time),
                )
          },
  )
}

private fun mergeToolTime(
    previousTime: OpenCodeToolTime?,
    nextTime: OpenCodeToolTime?,
): OpenCodeToolTime? =
    when {
      previousTime == null -> nextTime
      nextTime == null -> previousTime
      else ->
          nextTime.copy(
              start = nextTime.start ?: previousTime.start,
              end = nextTime.end ?: previousTime.end,
          )
    }

private fun String.toEnclosedMessageRole(): EnclosedMessageRole? =
    when (lowercase()) {
      "user" -> EnclosedMessageRole.User
      "assistant" -> EnclosedMessageRole.Assistant
      else -> null
    }

private fun OpenCodeStepTokens.toEnclosedStepTokenStats(): EnclosedStepTokenStats =
    EnclosedStepTokenStats(
        totalTokenCount = total,
        inputTokenCount = input,
        outputTokenCount = output,
        reasoningTokenCount = reasoning,
        cachedTokenStats =
            cache?.let {
              EnclosedStepCachedTokenStats(
                  readTokenCount = it.read,
                  writeTokenCount = it.write,
              )
            },
    )

private fun OpenCodeMessageError.toEnclosedMessageError(): EnclosedMessageError =
    EnclosedMessageError(
        name = name,
        message = message ?: data?.messageText,
        providerId = data?.providerId?.let(::EnclosedProviderId),
        modelId = data?.modelId?.let(::EnclosedModelId),
    )
