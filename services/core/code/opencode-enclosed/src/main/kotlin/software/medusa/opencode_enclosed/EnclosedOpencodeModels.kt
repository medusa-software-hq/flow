package software.medusa.opencode_enclosed

import java.time.Instant

@JvmInline
value class EnclosedProviderId(val value: String) {
  companion object {
    val OpenAI = EnclosedProviderId("openai")
    val GithubCopilot = EnclosedProviderId("github-copilot")
  }
}

@JvmInline
value class EnclosedModelId(val value: String) {
  companion object {
    val Gpt5_4 = EnclosedModelId("gpt-5.4")
  }
}

@JvmInline value class EnclosedToolCallId(val value: String)

@JvmInline value class EnclosedMessageId(val value: String)

@JvmInline value class EnclosedToolName(val value: String)

data class EnclosedModelRef(
    val providerId: EnclosedProviderId,
    val modelId: EnclosedModelId,
) {
  object GithubCopilot {
    val Gpt5_4 =
        EnclosedModelRef(
            providerId = EnclosedProviderId.GithubCopilot,
            modelId = EnclosedModelId.Gpt5_4,
        )
  }
}

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
