package software.medusa.flow.harness.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses one line of the `claude` CLI's `stream-json` output into an [HrsClaudeMessage].
 *
 * All field access lives here on purpose (see [HrsClaudeMessage]): the exact wire shapes are only
 * confirmed by A1's contract test, so keeping every key lookup in this one object makes protocol
 * drift a single-file fix. Navigation is done over a raw [JsonObject] rather than `@Serializable`
 * data classes so an unexpected/renamed field degrades to `null` instead of throwing.
 */
object HrsClaudeStreamParser {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Parses [line] into a message, or returns `null` for a blank line or one that is not a JSON
   * object (both are simply skipped by the driver rather than treated as failures).
   */
  fun parseLine(
      line: String,
  ): HrsClaudeMessage? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null

    val root =
        try {
          json.parseToJsonElement(trimmed).jsonObject
        } catch (_: Exception) {
          return null
        }

    return when (val type = root.stringField("type")) {
      "system" -> parseSystem(root)
      "assistant" -> parseAssistant(root)
      "result" -> parseResult(root)
      else -> HrsClaudeMessage.Unknown(type = type)
    }
  }

  private fun parseSystem(
      root: JsonObject,
  ): HrsClaudeMessage =
      when (root.stringField("subtype")) {
        "init" ->
            HrsClaudeMessage.SystemInit(
                sessionId = root.stringField("session_id"),
                model = root.stringField("model"),
                tools =
                    root["tools"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList(),
            )
        else -> HrsClaudeMessage.Unknown(type = "system")
      }

  private fun parseAssistant(
      root: JsonObject,
  ): HrsClaudeMessage {
    // Shape: {"type":"assistant","message":{"content":[{"type":"text","text":"…"}, …]}}
    val content = root["message"]?.jsonObjectOrNull()?.get("content")?.jsonArrayOrNull()
    val text =
        content
            ?.mapNotNull { block ->
              val blockObject = block.jsonObjectOrNull() ?: return@mapNotNull null
              if (blockObject.stringField("type") == "text") blockObject.stringField("text")
              else null
            }
            ?.joinToString(separator = "\n")
            .orEmpty()

    return HrsClaudeMessage.Assistant(text = text)
  }

  private fun parseResult(
      root: JsonObject,
  ): HrsClaudeMessage =
      HrsClaudeMessage.Result(
          isError =
              root["is_error"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
          subtype = root.stringField("subtype"),
          totalCostUsd = root["total_cost_usd"]?.jsonPrimitive?.doubleOrNull,
          numTurns = root["num_turns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
          durationMs = root["duration_ms"]?.jsonPrimitive?.longOrNull,
      )

  private fun JsonObject.stringField(
      name: String,
  ): String? = this[name]?.jsonPrimitive?.contentOrNull

  private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
      try {
        jsonObject
      } catch (_: Exception) {
        null
      }

  private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
      try {
        jsonArray
      } catch (_: Exception) {
        null
      }
}
