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
