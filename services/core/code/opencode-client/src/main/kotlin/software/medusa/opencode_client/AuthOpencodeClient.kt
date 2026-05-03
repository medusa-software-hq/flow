package software.medusa.opencode_client

interface AuthOpencodeClient : BaseOpencodeClient {
  fun authenticate(providerId: String, apiKey: String)
}
