package software.medusa.opencode_client

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.QueryParams
import com.linecorp.armeria.common.auth.AuthToken
import java.net.URI
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val basicAuthUsername = "opencode"

class OpencodeClientImpl(
    private val webClient: WebClient,
) : FullOpencodeClient {
  companion object {
    private val json: Json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
      explicitNulls = false
    }

    fun build(
        serverUri: URI,
        serverPassword: String,
    ): OpencodeClientImpl {
      val webClient =
          WebClient.builder(serverUri)
              .apply {
                responseTimeoutMillis(0)

                addHeader(
                    HttpHeaderNames.AUTHORIZATION,
                    AuthToken.ofBasic(basicAuthUsername, serverPassword).asHeaderValue(),
                )

                addHeader(
                    HttpHeaderNames.CONTENT_TYPE,
                    MediaType.JSON_UTF_8,
                )

                addHeader(
                    HttpHeaderNames.ACCEPT,
                    MediaType.JSON_UTF_8,
                )
              }
              .build()

      return OpencodeClientImpl(webClient = webClient)
    }
  }

  override fun checkHealth(): OpenCodeHealthResponse =
      webClient.get("/global/health").extract(OpenCodeHealthResponse.serializer())

  override fun createSession(title: String?): OpenCodeCreateSessionResponse =
      webClient
          .post(
              "/session",
              OpenCodeCreateSessionRequest(
                  title = title ?: "(default title)",
              ),
              OpenCodeCreateSessionRequest.serializer(),
          )
          .extract(OpenCodeCreateSessionResponse.serializer())

  override fun listProviders(): OpenCodeProviderListResponse =
      webClient.get("/provider").extract(OpenCodeProviderListResponse.serializer())

  override fun authenticate(providerId: String, apiKey: String) {
    webClient
        .put(
            "/auth/$providerId",
            OpenCodeAuthRequest(key = apiKey),
            OpenCodeAuthRequest.serializer(),
        )
        .joinOptimistically()
  }

  override fun enableLocalPermissions() {
    webClient
        .patch(
            "/config",
            OpenCodePermissionConfigRequest(
                permission =
                    OpenCodePermissionConfig(
                        edit = "allow",
                        bash = "allow",
                        webfetch = "allow",
                        externalDirectory = "allow",
                    ),
            ),
            OpenCodePermissionConfigRequest.serializer(),
        )
        .joinOptimistically()
  }

  override fun sendMessage(
      sessionId: String,
      text: String,
      providerId: String?,
      modelId: String?,
      agent: String?,
      noReply: Boolean,
      system: String?,
  ): OpenCodeMessage =
      webClient
          .post(
              path = "/session/$sessionId/message",
              content =
                  OpenCodeSendMessageRequest(
                      parts =
                          listOf(
                              OpenCodeMessagePart(
                                  type = "text",
                                  text = text,
                              ),
                          ),
                      model =
                          OpenCodeModelRef(
                              providerId = providerId!!,
                              modelId = modelId!!,
                          ),
                      agent = agent?.takeIf { it.isNotBlank() },
                      noReply = noReply,
                      system = system?.takeIf { it.isNotBlank() },
                  ),
              serializer = OpenCodeSendMessageRequest.serializer(),
          )
          .extract(OpenCodeMessage.serializer())

  override fun listMessages(
      sessionId: String,
      limit: Int?,
  ): List<OpenCodeMessage> =
      webClient
          .get(
              "/session/$sessionId/message",
              QueryParams.builder().apply { limit?.let { addInt("limit", it) } }.build(),
          )
          .extract(ListSerializer(OpenCodeMessage.serializer()))

  private fun <T> WebClient.post(
      path: String,
      content: T,
      serializer: KSerializer<T>,
  ): HttpResponse =
      this.post(
          path,
          json.encodeToString(
              serializer = serializer,
              value = content,
          ),
      )

  private fun <T> WebClient.patch(
      path: String,
      content: T,
      serializer: KSerializer<T>,
  ): HttpResponse =
      this.patch(
          path,
          json.encodeToString(
              serializer = serializer,
              value = content,
          ),
      )

  private fun <T> WebClient.put(
      path: String,
      content: T,
      serializer: KSerializer<T>,
  ): HttpResponse =
      this.put(
          path,
          json.encodeToString(
              serializer = serializer,
              value = content,
          ),
      )

  private fun HttpResponse.joinOptimistically(): AggregatedHttpResponse {
    val aggregatedHttpResponse = aggregate().join()

    val responseStatus = aggregatedHttpResponse.status()

    if (!responseStatus.isSuccess) {
      val contentUtf8 = aggregatedHttpResponse.contentUtf8()
      throw IllegalStateException(
          "OpenCode server request failed with status ${responseStatus.code()}: ${contentUtf8.ifBlank { "(blank)" }}"
      )
    }

    return aggregatedHttpResponse
  }

  private fun <T> HttpResponse.extract(
      responseDeserializer: DeserializationStrategy<T>,
  ): T {
    val aggregatedHttpResponse = joinOptimistically()

    val responseBody = aggregatedHttpResponse.contentUtf8()

    return json.decodeFromString(
        deserializer = responseDeserializer,
        string = responseBody,
    )
  }
}
