package software.medusa.opencode_client

interface WorkspaceOpencodeClient : BaseOpencodeClient {
  fun createSession(title: String?): OpenCodeCreateSessionResponse

  fun enableLocalPermissions()

  fun listProviders(): OpenCodeProviderListResponse

  fun sendMessage(
      sessionId: String,
      text: String,
      providerId: String?,
      modelId: String?,
      agent: String?,
      noReply: Boolean,
      system: String?,
  ): OpenCodeMessage

  fun listMessages(sessionId: String, limit: Int?): List<OpenCodeMessage>
}
