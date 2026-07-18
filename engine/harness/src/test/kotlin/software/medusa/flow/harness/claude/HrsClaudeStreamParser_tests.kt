package software.medusa.flow.harness.claude

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [HrsClaudeStreamParser] on sample stream-json lines. */
class HrsClaudeStreamParser_tests {
  @Test
  fun `parses a system init line, capturing session id, model and tools`() {
    val line =
        """{"type":"system","subtype":"init","session_id":"abc-123","model":"claude-sonnet-4-6","tools":["Read","Edit","Bash"],"cwd":"/tmp/x"}"""

    val message = assertIs<HrsClaudeMessage.SystemInit>(HrsClaudeStreamParser.parseLine(line))
    assertEquals("abc-123", message.sessionId)
    assertEquals("claude-sonnet-4-6", message.model)
    assertEquals(listOf("Read", "Edit", "Bash"), message.tools)
  }

  @Test
  fun `parses an assistant line, flattening text content blocks`() {
    val line =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"hello"},{"type":"tool_use","name":"Edit"},{"type":"text","text":"world"}]}}"""

    val message = assertIs<HrsClaudeMessage.Assistant>(HrsClaudeStreamParser.parseLine(line))
    assertEquals("hello\nworld", message.text)
  }

  @Test
  fun `parses tool_use blocks into one-line action summaries`() {
    val line =
        """{"type":"assistant","message":{"role":"assistant","content":[
          {"type":"text","text":"working"},
          {"type":"tool_use","name":"Edit","input":{"file_path":"src/App.tsx"}},
          {"type":"tool_use","name":"Write","input":{"file_path":"src/New.kt"}},
          {"type":"tool_use","name":"Read","input":{"file_path":"README.md"}},
          {"type":"tool_use","name":"Bash","input":{"command":"gradle test --info"}},
          {"type":"tool_use","name":"Grep","input":{"pattern":"TODO"}},
          {"type":"tool_use","name":"MysteryTool","input":{}}
        ]}}"""
            .replace("\n", "")

    val message = assertIs<HrsClaudeMessage.Assistant>(HrsClaudeStreamParser.parseLine(line))
    assertEquals("working", message.text)
    assertEquals(
        listOf(
            "edited `src/App.tsx`",
            "wrote `src/New.kt`",
            "read `README.md`",
            "ran `gradle test --info`",
            "searched `TODO`",
            "used MysteryTool",
        ),
        message.toolActions,
    )
  }

  @Test
  fun `a long bash command is truncated in the action summary`() {
    val longCommand = "echo " + "x".repeat(200)
    val line =
        """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"$longCommand"}}]}}"""

    val message = assertIs<HrsClaudeMessage.Assistant>(HrsClaudeStreamParser.parseLine(line))
    val summary = message.toolActions.single()
    assertTrue(summary.startsWith("ran `echo "), summary)
    assertTrue(summary.endsWith("…`"), summary)
    // The backtick-wrapped command stays bounded (cap 60 + the "…" marker).
    assertTrue(summary.length < 80, "summary should be truncated: ${summary.length}")
  }

  @Test
  fun `parses a successful result line with accounting fields`() {
    val line =
        """{"type":"result","subtype":"success","is_error":false,"total_cost_usd":0.0421,"num_turns":5,"duration_ms":8123}"""

    val message = assertIs<HrsClaudeMessage.Result>(HrsClaudeStreamParser.parseLine(line))
    assertEquals(false, message.isError)
    assertEquals("success", message.subtype)
    assertEquals(0.0421, message.totalCostUsd)
    assertEquals(5, message.numTurns)
    assertEquals(8123L, message.durationMs)
  }

  @Test
  fun `parses an error result line, including a cap subtype`() {
    val line =
        """{"type":"result","subtype":"error_max_budget_usd","is_error":true,"total_cost_usd":0.5}"""

    val message = assertIs<HrsClaudeMessage.Result>(HrsClaudeStreamParser.parseLine(line))
    assertTrue(message.isError)
    assertEquals("error_max_budget_usd", message.subtype)
  }

  @Test
  fun `an unknown message type degrades to Unknown rather than throwing`() {
    val line = """{"type":"user","message":{"role":"user"}}"""

    val message = assertIs<HrsClaudeMessage.Unknown>(HrsClaudeStreamParser.parseLine(line))
    assertEquals("user", message.type)
  }

  @Test
  fun `unknown fields on a known type are tolerated`() {
    val line =
        """{"type":"result","subtype":"success","is_error":false,"brand_new_field":42,"nested":{"x":1}}"""

    val message = assertIs<HrsClaudeMessage.Result>(HrsClaudeStreamParser.parseLine(line))
    assertEquals("success", message.subtype)
  }

  @Test
  fun `blank and non-json lines return null`() {
    assertNull(HrsClaudeStreamParser.parseLine(""))
    assertNull(HrsClaudeStreamParser.parseLine("   "))
    assertNull(HrsClaudeStreamParser.parseLine("not json at all"))
    assertNull(HrsClaudeStreamParser.parseLine("[1,2,3]"))
  }
}
