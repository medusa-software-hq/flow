package software.medusa.openai_client

import com.aallam.openai.api.chat.ChatMessage
import kotlinx.serialization.Serializable

@Serializable
enum class OpenAiRole {
  System,
  User,
  Assistant,
}

@Serializable
data class OpenAiMessage(
    val role: OpenAiRole,
    val text: String,
    val name: String? = null,
) {
  internal fun toSdkChatMessage(): ChatMessage =
      when (role) {
        OpenAiRole.System -> ChatMessage.Companion.System(content = text, name = name)
        OpenAiRole.User -> ChatMessage.Companion.User(content = text, name = name)
        OpenAiRole.Assistant -> ChatMessage.Companion.Assistant(content = text, name = name)
      }
}

@Serializable
data class OpenAiChat(
    val messages: List<OpenAiMessage>,
) {
  init {
    require(messages.isNotEmpty()) { "Completion input requires at least one message" }
  }

  internal fun toSdkMessages(): List<ChatMessage> = messages.map(OpenAiMessage::toSdkChatMessage)
}
