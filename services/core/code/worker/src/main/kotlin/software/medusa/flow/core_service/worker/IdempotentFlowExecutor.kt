package software.medusa.flow.core_service.worker

import org.slf4j.LoggerFactory
import software.medusa.flow.core_service.flows.BlankTaskResult
import software.medusa.flow.core_service.flows.FeatureTaskResult
import software.medusa.flow.core_service.flows.MergeTaskResult
import software.medusa.flow.core_service.flows.Task
import software.medusa.flow.core_service.flows.TaskId
import software.medusa.flow.core_service.worker.FlowExecutor.BaselineContext
import software.medusa.flow.core_service.worker.FlowExecutor.EnvironmentContext
import software.medusa.git.GitCommitHash

class IdempotentFlowExecutor(
    private val properTaskExecutor: ProperFlowExecutor,
) : FlowExecutor {
  companion object {
    private val logger = LoggerFactory.getLogger(FlowExecutor::class.java)
  }

  context(environmentContext: EnvironmentContext)
  override suspend fun initializeFlow(): BaselineContext = properTaskExecutor.initializeFlow()

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  override suspend fun executeBlankTask(
      taskId: TaskId,
      inputCommitHash: GitCommitHash?,
  ): BlankTaskResult {
    val restoredResult =
        environmentContext.taskResultRestorer.restoreTaskResult(
            taskId = taskId,
        ) as? BlankTaskResult

    return restoredResult
        ?: properTaskExecutor.executeBlankTask(
            taskId = taskId,
            inputCommitHash = inputCommitHash,
        )
  }

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  override suspend fun executeFeatureTask(
      taskId: TaskId,
      taskDefinition: Task.FeatureDefinition,
      inputCommitHash: GitCommitHash?,
  ): FeatureTaskResult {
    val restoredResult =
        environmentContext.taskResultRestorer.restoreTaskResult(
            taskId = taskId,
        ) as? FeatureTaskResult

    return restoredResult
        ?: properTaskExecutor.executeFeatureTask(
            taskId = taskId,
            taskDefinition = taskDefinition,
            inputCommitHash = inputCommitHash,
        )
  }

  context(environmentContext: EnvironmentContext, baselineContext: BaselineContext)
  override suspend fun executeMergeTask(
      taskId: TaskId,
      baseCommitHashes: Set<GitCommitHash>,
  ): MergeTaskResult {
    val restoredResult =
        environmentContext.taskResultRestorer.restoreTaskResult(
            taskId = taskId,
        ) as? MergeTaskResult

    return restoredResult
        ?: properTaskExecutor.executeMergeTask(
            taskId = taskId,
            baseCommitHashes = baseCommitHashes,
        )
  }
}
