package software.medusa.flow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Builds the action-specific JSON payload stored on an [OutboxEntry]. */
object OutboxPayloads {
  fun label(
      name: String,
  ): String = Json.encodeToString(buildJsonObject { put("label", name) })

  fun comment(
      body: String,
  ): String = Json.encodeToString(buildJsonObject { put("body", body) })

  /** Close carries no payload. */
  const val closeIssue = "{}"
}
