package software.medusa.opencode_client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class OpenCodeHealthResponse(val status: String? = null)

@Serializable data class OpenCodeCreateSessionRequest(val title: String? = null)

@Serializable
data class OpenCodeCreateSessionResponse(
    val id: String,
    val title: String? = null,
)

@Serializable
data class OpenCodeAuthRequest(
    val type: String = "api",
    val key: String,
)

@Serializable
data class OpenCodePermissionConfigRequest(
    val permission: OpenCodePermissionConfig,
)

@Serializable
data class OpenCodePermissionConfig(
    val edit: String,
    val bash: String,
    val webfetch: String,
    @SerialName("external_directory") val externalDirectory: String,
)

@Serializable
data class OpenCodeProviderListResponse(
    val all: List<OpenCodeProvider> = emptyList(),
    val connected: List<String> = emptyList(),
    val default: Map<String, String> = emptyMap(),
)

@Serializable
data class OpenCodeProvider(
    val id: String,
    val name: String? = null,
    val models: Map<String, OpenCodeModel> = emptyMap(),
)

@Serializable
data class OpenCodeModel(
    val id: String,
    val name: String? = null,
)

@Serializable
data class OpenCodeSendMessageRequest(
    val parts: List<OpenCodeMessagePart>,
    val model: OpenCodeModelRef? = null,
    val agent: String? = null,
    val noReply: Boolean = false,
    val system: String? = null,
)

@Serializable
data class OpenCodeModelRef(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
)

@Serializable
data class OpenCodeMessage(
    val id: String? = null,
    val parts: List<OpenCodeMessagePart> = emptyList(),
    val info: OpenCodeMessageInfo? = null,
    val role: String? = null,
    val time: OpenCodeMessageTime? = null,
)

@Serializable
data class OpenCodeMessageInfo(
    val id: String? = null,
    val role: String? = null,
    val time: OpenCodeMessageTime? = null,
    val error: OpenCodeMessageError? = null,
)

@Serializable
data class OpenCodeMessageTime(
    val created: Long? = null,
    val completed: Long? = null,
)

@Serializable
data class OpenCodeMessagePart(
    val id: String? = null,
    val type: String,
    val text: String? = null,
    val tool: String? = null,
    @SerialName("callID") val callId: String? = null,
    val state: OpenCodeToolState? = null,
    val reason: String? = null,
    val snapshot: String? = null,
    val tokens: OpenCodeStepTokens? = null,
    val cost: Double? = null,
    val hash: String? = null,
    val files: List<String> = emptyList(),
)

@Serializable
data class OpenCodeToolState(
    val status: String? = null,
    val input: JsonObject? = null,
    val output: String? = null,
    val metadata: JsonObject? = null,
    val title: String? = null,
    val error: String? = null,
    val time: OpenCodeToolTime? = null,
)

@Serializable
data class OpenCodeToolTime(
    val start: Long? = null,
    val end: Long? = null,
)

@Serializable
data class OpenCodeStepTokens(
    val total: Long? = null,
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: OpenCodeStepTokenCache? = null,
)

@Serializable
data class OpenCodeStepTokenCache(
    val read: Long? = null,
    val write: Long? = null,
)

@Serializable
data class OpenCodeMessageError(
    val name: String? = null,
    val message: String? = null,
    val data: OpenCodeMessageErrorData? = null,
)

@Serializable
data class OpenCodeMessageErrorData(
    @SerialName("message") val messageText: String? = null,
    @SerialName("providerID") val providerId: String? = null,
    @SerialName("modelID") val modelId: String? = null,
)
