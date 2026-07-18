package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WrkAgentActionCoalescer_tests {
  @Test
  fun `emits every action verbatim while under the cap`() {
    val coalescer = WrkAgentActionCoalescer(maxEvents = 3)

    val decisions = listOf("a", "b", "c").map { coalescer.offer(it) }

    assertEquals(
        listOf("a", "b", "c"),
        decisions.map { assertIs<WrkAgentActionCoalescer.Decision.Emit>(it).message },
    )
  }

  @Test
  fun `a chatty run stays within the cap - one notice then drops`() {
    val cap = 5
    val coalescer = WrkAgentActionCoalescer(maxEvents = cap)

    // Far more actions than the cap: a genuinely chatty tool stream.
    val decisions = (1..500).map { coalescer.offer("action $it") }

    val emitted = decisions.filterIsInstance<WrkAgentActionCoalescer.Decision.Emit>()
    val dropped = decisions.filterIsInstance<WrkAgentActionCoalescer.Decision.Drop>()

    // At most `cap` real actions plus exactly one truncation notice ever cross the wire.
    assertEquals(cap + 1, emitted.size)
    assertEquals(500 - (cap + 1), dropped.size)

    // The first `cap` are the real actions, verbatim.
    assertEquals((1..cap).map { "action $it" }, emitted.take(cap).map { it.message })

    // The (cap+1)-th emit is the single truncation notice; everything after is dropped.
    val notice = emitted.last().message
    assertTrue(notice.contains("omitted"), notice)
    assertTrue(notice.contains(cap.toString()), notice)
  }

  @Test
  fun `the default cap matches the display-only contract`() {
    assertEquals(200, WrkAgentActionCoalescer.defaultMaxAgentActionEvents)
  }
}
