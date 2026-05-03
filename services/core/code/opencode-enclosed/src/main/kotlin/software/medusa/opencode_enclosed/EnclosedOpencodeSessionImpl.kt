package software.medusa.opencode_enclosed

import software.medusa.opencode_client.FullOpencodeClient

class EnclosedOpencodeSessionImpl(
    override val title: String,
    override val sessionId: String,
    override val serverUrl: String,
    private val opencodeClient: FullOpencodeClient,
) : EnclosedOpencodeSession {
  override fun authenticate(providerId: String, apiKey: String): Boolean {
    opencodeClient.authenticate(providerId = providerId, apiKey = apiKey)
    return true
  }

  override fun sendMessage(
      model: EnclosedModelRef,
      text: String,
  ): EnclosedMessage =
      opencodeClient
          .sendMessage(
              sessionId = sessionId,
              text = text,
              providerId = model.providerId.value,
              modelId = model.modelId.value,
              agent = null,
              noReply = false,
              system = null,
          )
          .toEnclosedMessage()

  override fun listMessages(limit: Int?): List<EnclosedMessage> =
      opencodeClient.listMessages(sessionId = sessionId, limit = limit).map {
        it.toEnclosedMessage()
      }
}
