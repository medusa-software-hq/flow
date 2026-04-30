package software.medusa.opencode_enclosed

import java.time.Instant

@JvmInline value class EnclosedProviderId(val value: String)

@JvmInline value class EnclosedModelId(val value: String)

@JvmInline value class EnclosedToolCallId(val value: String)

@JvmInline value class EnclosedMessageId(val value: String)

@JvmInline value class EnclosedToolName(val value: String)

data class EnclosedSupportedModel(
    val providerId: EnclosedProviderId,
    val modelId: EnclosedModelId,
)

enum class EnclosedMessageRole {
  User,
  Assistant,
}

data class EnclosedMessage(
    val id: EnclosedMessageId?,
    val role: EnclosedMessageRole?,
    val createdAt: Instant?,
    val completedAt: Instant?,
    val text: String,
    val toolCalls: List<EnclosedToolCall>,
    val error: EnclosedMessageError?,
    val usage: EnclosedStepTokenStats?,
    val estimatedCostUsd: Double?,
)

data class EnclosedToolCall(
    val id: EnclosedToolCallId,
    val toolName: EnclosedToolName,
    val title: String?,
    val status: String?,
    val inputJson: String?,
    val outputText: String?,
    val errorText: String?,
    val startedAt: Instant?,
    val endedAt: Instant?,
)

data class EnclosedStepTokenStats(
    val totalTokenCount: Long?,
    val inputTokenCount: Long?,
    val outputTokenCount: Long?,
    val reasoningTokenCount: Long?,
    val cachedTokenStats: EnclosedStepCachedTokenStats?,
)

data class EnclosedStepCachedTokenStats(
    val readTokenCount: Long?,
    val writeTokenCount: Long?,
)

data class EnclosedMessageError(
    val name: String?,
    val message: String?,
    val providerId: EnclosedProviderId?,
    val modelId: EnclosedModelId?,
)
