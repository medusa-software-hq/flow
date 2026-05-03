package software.medusa.opencode_enclosed

interface EnclosedOpencodeSession {
  val title: String

  val sessionId: String

  val serverUrl: String

  fun authenticate(providerId: String, apiKey: String): Boolean

  fun sendMessage(
      model: EnclosedModelRef,
      text: String,
  ): EnclosedMessage

  fun listMessages(limit: Int?): List<EnclosedMessage>
}
