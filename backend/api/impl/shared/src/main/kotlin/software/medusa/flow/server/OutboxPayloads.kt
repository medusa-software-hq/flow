package software.medusa.flow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Builds and reads the action-specific JSON payload stored on an [OutboxEntry]. */
object OutboxPayloads {
  fun label(
      name: String,
  ): String = Json.encodeToString(buildJsonObject { put("label", name) })

  fun comment(
      body: String,
  ): String = Json.encodeToString(buildJsonObject { put("body", body) })

  /** Close carries no payload. */
  const val closeIssue = "{}"

  /** The label name from an `ADD_LABEL` / `REMOVE_LABEL` payload. */
  fun readLabel(
      payload: String,
  ): String = Json.parseToJsonElement(payload).jsonObject.getValue("label").jsonPrimitive.content

  /** The comment body from a `POST_COMMENT` payload. */
  fun readComment(
      payload: String,
  ): String = Json.parseToJsonElement(payload).jsonObject.getValue("body").jsonPrimitive.content
}
