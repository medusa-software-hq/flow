package software.medusa.opencode_client

interface BaseOpencodeClient {
  fun checkHealth(): OpenCodeHealthResponse
}
