package software.medusa.flow.worker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import software.medusa.flow.v1.Engine

class WrkConfig_tests {
  @Test
  fun `FLOW_WORKER_ENGINES unset defaults to builtin only`() {
    assertEquals(
        listOf(Engine.ENGINE_BUILTIN),
        WrkConfig.parseWorkerEngines(lookup = { null }),
    )
  }

  @Test
  fun `FLOW_WORKER_ENGINES blank defaults to builtin only`() {
    assertEquals(
        listOf(Engine.ENGINE_BUILTIN),
        WrkConfig.parseWorkerEngines(
            lookup = { "   ".takeIf { name -> name == "FLOW_WORKER_ENGINES" } }
        ),
    )
  }

  @Test
  fun `FLOW_WORKER_ENGINES parses an ordered comma-separated list`() {
    assertEquals(
        listOf(Engine.ENGINE_BUILTIN, Engine.ENGINE_CLAUDE),
        WrkConfig.parseWorkerEngines(
            lookup = { name -> "builtin, claude".takeIf { name == "FLOW_WORKER_ENGINES" } },
        ),
    )
  }

  @Test
  fun `FLOW_WORKER_ENGINES preserves order with claude first`() {
    assertEquals(
        listOf(Engine.ENGINE_CLAUDE, Engine.ENGINE_BUILTIN),
        WrkConfig.parseWorkerEngines(
            lookup = { name -> "claude,builtin".takeIf { name == "FLOW_WORKER_ENGINES" } },
        ),
    )
  }

  @Test
  fun `FLOW_WORKER_ENGINES rejects an unknown engine token`() {
    val error =
        assertFailsWith<IllegalStateException> {
          WrkConfig.parseWorkerEngines(
              lookup = { name -> "builtin,gpt".takeIf { name == "FLOW_WORKER_ENGINES" } },
          )
        }
    assertEquals(true, error.message!!.contains("gpt"))
  }
}
