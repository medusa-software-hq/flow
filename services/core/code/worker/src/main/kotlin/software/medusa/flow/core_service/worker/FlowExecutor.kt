package software.medusa.flow.core_service.worker

import java.util.UUID
import software.medusa.flow.core_service.flows.FeatureTaskResult
import software.medusa.flow.core_service.flows.FlowId
import software.medusa.flow.core_service.flows.MergeTaskResult
import software.medusa.flow.core_service.flows.Task
import software.medusa.flow.core_service.flows.TaskId
import software.medusa.flow.core_service.flows.TaskProgressSaver
import software.medusa.flow.core_service.flows.TaskResultRestorer
import software.medusa.git.GitCommitHash

interface FlowExecutor {
  interface EnvironmentContext {
    val flowId: FlowId
    val taskResultRestorer: TaskResultRestorer
    val taskProgressSaver: TaskProgressSaver
  }

  interface BaselineContext {
    val flowUuid: UUID
    val rootCommitHash: GitCommitHash
  }

  interface TaskExecutionContext : EnvironmentContext, BaselineContext

  context(environmentContext: EnvironmentContext)
  suspend fun initializeFlow(): BaselineContext

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  suspend fun executeFeatureTask(
      taskId: TaskId,
      taskDefinition: Task.FeatureDefinition,
      inputCommitHash: GitCommitHash?,
  ): FeatureTaskResult

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  suspend fun executeMergeTask(
      taskId: TaskId,
      baseCommitHashes: Set<GitCommitHash>,
  ): MergeTaskResult
}
