package software.medusa.flow.harness.assistance

import software.medusa.flow.harness.leadership.HrsTaskDefinition

/**
 * A scripted [HrsAssistant]: always returns [result] and records every [runDelegation] call (in
 * order) in [invocations], for tests of a future driver that only needs to assert *what* it asked
 * the assistant to do, not how the assistant itself behaves.
 */
class FakeHrsAssistant(
    private val result: HrsAssistant.Result,
) : HrsAssistant {
  data class Invocation(
      val context: HrsAssistanceContext,
      val taskDefinition: HrsTaskDefinition,
      val toolbox: HrsToolbox,
  )

  val invocations: MutableList<Invocation> = mutableListOf()

  override suspend fun runDelegation(
      context: HrsAssistanceContext,
      taskDefinition: HrsTaskDefinition,
      toolbox: HrsToolbox,
  ): HrsAssistant.Result {
    invocations += Invocation(context = context, taskDefinition = taskDefinition, toolbox = toolbox)
    return result
  }
}
