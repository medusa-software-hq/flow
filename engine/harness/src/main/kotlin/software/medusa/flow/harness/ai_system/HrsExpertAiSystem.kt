package software.medusa.flow.harness.ai_system

import software.medusa.flow.harness.HrsTaskDescription

interface HrsExpertAiSystem {
  @JvmInline
  value class WorkspaceBrief(
      val body: String,
  )

  @JvmInline
  value class ImplementationPlan(
      val body: String,
  )

  suspend fun planImplementation(
      taskDescription: HrsTaskDescription,
      workspaceBrief: WorkspaceBrief,
  ): ImplementationPlan
}
