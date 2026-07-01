package software.medusa.flow.harness.ai_system

import software.medusa.commons.markdown.MdChapter
import software.medusa.commons.markdown.MdDocument
import software.medusa.flow.harness.HrsTaskDescription

interface HrsExpertAiSystem {
  @JvmInline
  value class WorkspaceBrief(
      val body: MdChapter,
  )

  @JvmInline
  value class ImplementationPlan(
      val body: MdDocument,
  )

  suspend fun planImplementation(
      taskDescription: HrsTaskDescription,
      workspaceBrief: WorkspaceBrief,
  ): ImplementationPlan
}
