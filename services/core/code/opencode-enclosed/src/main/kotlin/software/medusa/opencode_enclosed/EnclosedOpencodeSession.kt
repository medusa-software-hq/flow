package software.medusa.opencode_enclosed

interface EnclosedOpencodeSession {
  val title: String

  val sessionId: String

  val serverUrl: String

  fun authenticate(providerId: String, apiKey: String): Boolean

  fun sendMessage(
      text: String,
      model: EnclosedSupportedModel,
      agent: String?,
      noReply: Boolean,
      system: String?,
  ): EnclosedMessage

  fun listMessages(limit: Int?): List<EnclosedMessage>
}
